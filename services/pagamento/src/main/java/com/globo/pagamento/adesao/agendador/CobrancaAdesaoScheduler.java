package com.globo.pagamento.adesao.agendador;

import com.globo.pagamento.adesao.CobrancaAdesaoTentativa;
import com.globo.pagamento.adesao.CobrancaAdesaoTentativaRepository;
import com.globo.pagamento.cobranca.Cobranca;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.shared.contrato.AssinaturaAdesaoEsgotada;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Scheduler que cobra as tentativas de adesao prontas para cobranca.
 *
 * <p>Para cada tentativa elegivel, cria a cobranca no gateway e persiste o {@code paymentId}
 * devolvido na tentativa e na correlacao {@link Cobranca}.
 *
 * <p>Falhas tecnicas do gateway sao contabilizadas em {@link CobrancaAdesaoTentativa}; abaixo do
 * teto a tentativa permanece pendente com backoff, e no teto a tentativa e esgotada e o evento de
 * esgotamento e publicado na outbox.
 */
@Component
public class CobrancaAdesaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(CobrancaAdesaoScheduler.class);

  private final CobrancaAdesaoTentativaRepository tentativaRepository;
  private final CobrancaRepository cobrancaRepository;
  private final GatewayPagamentoClient gateway;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;
  private final int tetoFalhasTecnicas;
  private final long backoffFalhasTecnicasMs;

  /**
   * Cria o scheduler com os colaboradores de persistencia, gateway, outbox e configuracao.
   *
   * @param tentativaRepository repositorio de tentativas de adesao
   * @param cobrancaRepository repositorio de correlacao pos-gateway
   * @param gateway client do gateway de pagamento
   * @param outboxRepository repositorio da outbox, usado ao esgotar por falhas tecnicas
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   * @param tetoFalhasTecnicas numero de falhas tecnicas consecutivas que esgota a tentativa
   * @param backoffFalhasTecnicasMs intervalo de backoff entre falhas tecnicas
   * @throws IllegalArgumentException se {@code tetoFalhasTecnicas} for menor que 1
   */
  public CobrancaAdesaoScheduler(
      CobrancaAdesaoTentativaRepository tentativaRepository,
      CobrancaRepository cobrancaRepository,
      GatewayPagamentoClient gateway,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper,
      @Value("${app.adesao.teto-falhas-tecnicas}") int tetoFalhasTecnicas,
      @Value("${app.adesao.backoff-falhas-tecnicas-ms}") long backoffFalhasTecnicasMs) {
    if (tetoFalhasTecnicas < 1) {
      throw new IllegalArgumentException("tetoFalhasTecnicas deve ser maior que 0");
    }
    this.tentativaRepository = tentativaRepository;
    this.cobrancaRepository = cobrancaRepository;
    this.gateway = gateway;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
    this.tetoFalhasTecnicas = tetoFalhasTecnicas;
    this.backoffFalhasTecnicasMs = backoffFalhasTecnicasMs;
  }

  /**
   * Dispara a cobranca das tentativas prontas na cadencia configurada.
   *
   * <p>Ponto de entrada do agendamento: abre a transacao que envolve a cobranca das tentativas
   * prontas e delega o loop a {@link #cobrar()}.
   */
  @Transactional
  @Scheduled(
      initialDelayString = "${app.adesao.scheduler-delay-inicial-ms}",
      fixedDelayString = "${app.adesao.scheduler-intervalo-ms}")
  public void agendar() {
    cobrar();
  }

  /**
   * Cobra as tentativas de adesao prontas.
   *
   * <p>Itera sobre as tentativas elegiveis, cria a cobranca no gateway e persiste o {@code
   * paymentId} na tentativa e na correlacao {@link Cobranca}. A transacao envolve todo o corpo do
   * loop para segurar o {@code FOR UPDATE SKIP LOCKED} da selecao ate o {@code save}, atravessando
   * a chamada ao gateway.
   */
  @Transactional
  public void cobrar() {
    List<CobrancaAdesaoTentativa> tentativas = tentativaRepository.buscarProntasParaCobrar();
    log.atDebug()
        .addKeyValue("event", "cobranca_adesao_batch_inicio")
        .addKeyValue("tamanhoLote", tentativas.size())
        .log("Cobranca de adesoes iniciada");
    for (CobrancaAdesaoTentativa tentativa : tentativas) {
      CobrancaCriada cobranca;
      try {
        cobranca = gateway.criarCobranca(tentativa.getAssinaturaUuid(), tentativa.getValor());
      } catch (CobrancaGatewayIndisponivelException e) {
        tentativa.registrarFalhaTecnica();
        if (tentativa.esgotouFalhasTecnicas(tetoFalhasTecnicas)) {
          tentativa.esgotar();
          tentativaRepository.save(tentativa);
          gravarOutbox(tentativa);
          log.atWarn()
              .addKeyValue("event", "adesao_falhas_tecnicas_esgotadas")
              .addKeyValue("assinaturaId", tentativa.getAssinaturaUuid())
              .addKeyValue("falhasTecnicas", tentativa.getFalhasTecnicas())
              .addKeyValue("tetoFalhasTecnicas", tetoFalhasTecnicas)
              .addKeyValue("reasonCode", "teto_falhas_tecnicas")
              .setCause(e)
              .log("Falhas tecnicas esgotaram a adesao");
        } else {
          tentativa.agendarPara(Instant.now().plusMillis(backoffFalhasTecnicasMs));
          tentativaRepository.save(tentativa);
          log.atWarn()
              .addKeyValue("event", "adesao_falha_tecnica_gateway")
              .addKeyValue("assinaturaId", tentativa.getAssinaturaUuid())
              .addKeyValue("falhasTecnicas", tentativa.getFalhasTecnicas())
              .addKeyValue("tetoFalhasTecnicas", tetoFalhasTecnicas)
              .addKeyValue("reasonCode", "cobranca_gateway_indisponivel")
              .log("Falha tecnica ao cobrar a adesao");
        }
        continue;
      }
      tentativa.registrarCobranca(cobranca.paymentId());
      tentativaRepository.save(tentativa);
      cobrancaRepository.save(
          new Cobranca(
              tentativa.getAssinaturaUuid(), cobranca.paymentId(), StatusCobranca.PENDING));
      log.atInfo()
          .addKeyValue("event", "adesao_cobranca_criada")
          .addKeyValue("assinaturaId", tentativa.getAssinaturaUuid())
          .log("Cobranca de adesao criada");
    }
    log.atDebug()
        .addKeyValue("event", "cobranca_adesao_batch_fim")
        .addKeyValue("tamanhoLote", tentativas.size())
        .log("Cobranca de adesoes concluida");
  }

  private void gravarOutbox(CobrancaAdesaoTentativa tentativa) {
    UUID eventId = UUID.randomUUID();
    AssinaturaAdesaoEsgotada evento =
        new AssinaturaAdesaoEsgotada(
            eventId, Instant.now(), UUID.fromString(tentativa.getAssinaturaUuid()));
    String payload = objectMapper.writeValueAsString(evento);
    outboxRepository.save(
        OutboxEvent.criar(
            eventId,
            "Cobranca",
            UUID.fromString(tentativa.getAssinaturaUuid()),
            "AssinaturaAdesaoEsgotada",
            payload));
  }
}
