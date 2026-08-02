package com.globo.pagamento.renovacao.agendador;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduler que cobra as tentativas de renovacao prontas para cobranca.
 *
 * <p>Para cada tentativa elegivel, carrega o pagamento da renovacao correspondente, cria a cobranca
 * no gateway e persiste o {@code paymentId} devolvido na tentativa.
 */
@Component
public class CobrancaRenovacaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(CobrancaRenovacaoScheduler.class);

  private final TentativaCobrancaRepository tentativaCobrancaRepository;
  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final GatewayPagamentoClient gateway;

  /**
   * Cria o scheduler com os colaboradores de persistencia e gateway.
   *
   * @param tentativaCobrancaRepository repositorio de tentativas de cobranca
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao
   * @param gateway client do gateway de pagamento
   */
  public CobrancaRenovacaoScheduler(
      TentativaCobrancaRepository tentativaCobrancaRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gateway) {
    this.tentativaCobrancaRepository = tentativaCobrancaRepository;
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.gateway = gateway;
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
   * Falhas tecnicas do gateway ({@link CobrancaGatewayIndisponivelException}) pulam a tentativa,
   * que permanece pendente para o proximo ciclo; demais excecoes propagam, pois indicam bug.
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
        log.atWarn()
            .addKeyValue("event", "cobranca_renovacao_falha_gateway")
            .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
            .addKeyValue("numero", tentativa.getNumero())
            .addKeyValue("reasonCode", "cobranca_gateway_indisponivel")
            .log("Falha tecnica ao cobrar a renovacao");
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
}
