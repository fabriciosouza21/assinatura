package com.globo.pagamento.renovacao;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
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
   */
  @Transactional
  @Scheduled(fixedDelayString = "${app.renovacao.scheduler-intervalo-ms}")
  public void agendar() {
    cobrar();
  }

  /**
   * Cobra as tentativas prontas.
   *
   * <p>Itera sobre as tentativas elegiveis, resolve o pagamento da renovacao, cria a cobranca no
   * gateway e persiste o {@code paymentId} devolvido na tentativa correspondente. Falhas tecnicas
   * do gateway pulam a tentativa, que permanece pendente para o proximo ciclo.
   *
   * <p>A transacao envolve todo o corpo do loop para segurar o {@code FOR UPDATE SKIP LOCKED} da
   * selecao ate o {@code save}, atravessando a chamada ao gateway. Sem isso, o lock da tentativa
   * seria liberado no commit da leitura (cada metodo do repositorio tem sua propria transacao),
   * abrindo uma janela durante a chamada de rede em que outra instancia poderia selecionar a mesma
   * tentativa e cobrar duas vezes.
   */
  @Transactional
  public void cobrar() {
    for (TentativaCobranca tentativa : tentativaCobrancaRepository.buscarProntasParaCobrar()) {
      PagamentoRenovacao pagamento =
          pagamentoRenovacaoRepository.findByRenovacaoId(tentativa.getRenovacaoId()).orElseThrow();
      CobrancaCriada cobranca;
      try {
        cobranca =
            gateway.criarCobrancaRenovacao(
                tentativa.getRenovacaoId(), tentativa.getNumero(), pagamento.getValor());
      } catch (RuntimeException e) {
        log.warn(
            "Falha tecnica ao cobrar a tentativa {} da renovacao {}: {}",
            tentativa.getNumero(),
            tentativa.getRenovacaoId(),
            e.getMessage());
        continue;
      }
      tentativa.registrarCobranca(cobranca.paymentId());
      tentativaCobrancaRepository.save(tentativa);
    }
  }
}
