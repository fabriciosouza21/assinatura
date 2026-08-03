package com.globo.pagamento.renovacao.agendador;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import com.globo.pagamento.shared.contrato.RenovacaoTentativasEsgotadas;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scheduler que cobra as tentativas de renovacao prontas para cobranca.
 *
 * <p>Para cada tentativa elegivel, carrega o pagamento da renovacao correspondente, cria a cobranca
 * no gateway e persiste o {@code paymentId} devolvido na tentativa.
 *
 * <p>Falhas tecnicas do gateway ({@link CobrancaGatewayIndisponivelException}) nao decidem a
 * tentativa: sao contabilizadas em {@link TentativaCobranca#registrarFalhaTecnica()} e a tentativa
 * permanece pendente para o proximo ciclo. Quando o teto configurado e atingido, a renovacao esgota
 * as tentativas e publica {@link RenovacaoTentativasEsgotadas} na outbox, suspendendo a assinatura
 * pelo fluxo existente em vez de manter a cobranca eterna.
 */
@Component
public class CobrancaRenovacaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(CobrancaRenovacaoScheduler.class);

  private final TentativaCobrancaRepository tentativaCobrancaRepository;
  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final GatewayPagamentoClient gateway;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final int tetoFalhasTecnicas;

  /**
   * Cria o scheduler com os colaboradores de persistencia, gateway e outbox.
   *
   * @param tentativaCobrancaRepository repositorio de tentativas de cobranca
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao
   * @param gateway client do gateway de pagamento
   * @param outboxRepository repositorio da outbox, usado ao esgotar por falhas tecnicas
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   * @param tetoFalhasTecnicas numero de falhas tecnicas consecutivas que esgota a tentativa, via
   *     {@code app.renovacao.teto-falhas-tecnicas}
   * @throws IllegalArgumentException se {@code tetoFalhasTecnicas} for menor que 1
   */
  public CobrancaRenovacaoScheduler(
      TentativaCobrancaRepository tentativaCobrancaRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gateway,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      @Value("${app.renovacao.teto-falhas-tecnicas}") int tetoFalhasTecnicas) {
    if (tetoFalhasTecnicas < 1) {
      throw new IllegalArgumentException("tetoFalhasTecnicas deve ser maior que 0");
    }
    this.tentativaCobrancaRepository = tentativaCobrancaRepository;
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.gateway = gateway;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.tetoFalhasTecnicas = tetoFalhasTecnicas;
  }

  /**
   * Dispara a cobranca das tentativas prontas na cadencia configurada.
   *
   * <p>Ponto de entrada do agendamento: abre a transacao que envolve a cobranca das tentativas
   * prontas e delega o loop a {@link #cobrar()}.
   *
   * <p>O delay inicial adia a primeira execucao apos a subida do contexto. Em producao ele escalona
   * o arranque de varias instancias; nos testes que exercitam {@link #cobrar()} diretamente ele
   * evita que a execucao agendada dispute as mesmas tentativas via {@code FOR UPDATE SKIP LOCKED}.
   */
  @Transactional
  @Scheduled(
      initialDelayString = "${app.renovacao.scheduler-delay-inicial-ms}",
      fixedDelayString = "${app.renovacao.scheduler-intervalo-ms}")
  public void agendar() {
    cobrar();
  }

  /**
   * Cobra as tentativas prontas.
   *
   * <p>Itera sobre as tentativas elegiveis, resolve o pagamento da renovacao, cria a cobranca no
   * gateway e persiste o {@code paymentId} devolvido na tentativa correspondente. Tentativas sem
   * pagamento da renovacao correspondente sao puladas, pois indicam inconsistencia referencial.
   *
   * <p>Falhas tecnicas do gateway ({@link CobrancaGatewayIndisponivelException}) contabilizam a
   * falha na tentativa: abaixo do teto configurado a tentativa permanece pendente para o proximo
   * ciclo; no teto, a renovacao esgota as tentativas e publica {@link RenovacaoTentativasEsgotadas}
   * na outbox, suspendendo a assinatura pelo fluxo existente. Demais excecoes propagam, pois
   * indicam bug.
   *
   * <p>A transacao envolve todo o corpo do loop para segurar o {@code FOR UPDATE SKIP LOCKED} da
   * selecao ate o {@code save}, atravessando a chamada ao gateway. Sem isso, o lock da tentativa
   * seria liberado no commit da leitura (cada metodo do repositorio tem sua propria transacao),
   * abrindo uma janela durante a chamada de rede em que outra instancia poderia selecionar a mesma
   * tentativa e cobrar duas vezes.
   */
  @Transactional
  public void cobrar() {
    List<TentativaCobranca> tentativas = tentativaCobrancaRepository.buscarProntasParaCobrar();
    log.atDebug()
        .addKeyValue("event", "cobranca_renovacao_batch_inicio")
        .addKeyValue("tamanhoLote", tentativas.size())
        .log("Cobranca de renovacoes iniciada");
    for (TentativaCobranca tentativa : tentativas) {
      Optional<PagamentoRenovacao> pagamento =
          pagamentoRenovacaoRepository.findByRenovacaoId(tentativa.getRenovacaoId());
      if (pagamento.isEmpty()) {
        log.atWarn()
            .addKeyValue("event", "cobranca_renovacao_sem_pagamento")
            .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
            .addKeyValue("numero", tentativa.getNumero())
            .log("Pagamento da renovacao nao encontrado");
        continue;
      }
      PagamentoRenovacao pagamentoRenovacao = pagamento.get();
      CobrancaCriada cobranca;
      try {
        cobranca =
            gateway.criarCobrancaRenovacao(
                tentativa.getRenovacaoId(), tentativa.getNumero(), pagamentoRenovacao.getValor());
      } catch (CobrancaGatewayIndisponivelException e) {
        tentativa.registrarFalhaTecnica();
        if (tentativa.esgotouFalhasTecnicas(tetoFalhasTecnicas)) {
          tentativa.esgotar();
          tentativaCobrancaRepository.save(tentativa);
          gravarOutbox(pagamentoRenovacao);
          log.atWarn()
              .addKeyValue("event", "cobranca_renovacao_falhas_tecnicas_esgotadas")
              .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
              .addKeyValue("numero", tentativa.getNumero())
              .addKeyValue("falhasTecnicas", tentativa.getFalhasTecnicas())
              .addKeyValue("tetoFalhasTecnicas", tetoFalhasTecnicas)
              .addKeyValue("reasonCode", "teto_falhas_tecnicas")
              .setCause(e)
              .log("Falhas tecnicas esgotaram a renovacao");
        } else {
          tentativaCobrancaRepository.save(tentativa);
          log.atWarn()
              .addKeyValue("event", "cobranca_renovacao_falha_gateway")
              .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
              .addKeyValue("numero", tentativa.getNumero())
              .addKeyValue("falhasTecnicas", tentativa.getFalhasTecnicas())
              .addKeyValue("tetoFalhasTecnicas", tetoFalhasTecnicas)
              .addKeyValue("reasonCode", "cobranca_gateway_indisponivel")
              .log("Falha tecnica ao cobrar a renovacao");
        }
        continue;
      }
      tentativa.registrarCobranca(cobranca.paymentId());
      tentativaCobrancaRepository.save(tentativa);
    }
    log.atDebug()
        .addKeyValue("event", "cobranca_renovacao_batch_fim")
        .addKeyValue("tamanhoLote", tentativas.size())
        .log("Cobranca de renovacoes concluida");
  }

  private void gravarOutbox(PagamentoRenovacao pagamento) {
    UUID eventId = UUID.randomUUID();
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            eventId,
            Instant.now(),
            UUID.fromString(pagamento.getRenovacaoId()),
            UUID.fromString(pagamento.getAssinaturaId()),
            pagamento.getCicloReferencia());
    ObjectNode envelope = objectMapper.createObjectNode();
    ObjectNode corpo = objectMapper.valueToTree(evento);
    envelope.put("tipo", "ESGOTADO");
    envelope.setAll(corpo);
    String payload = objectMapper.writeValueAsString(envelope);
    outboxRepository.save(
        OutboxEvent.criar(
            eventId,
            "Renovacao",
            UUID.fromString(pagamento.getAssinaturaId()),
            "RenovacaoTentativasEsgotadas",
            payload));
  }
}
