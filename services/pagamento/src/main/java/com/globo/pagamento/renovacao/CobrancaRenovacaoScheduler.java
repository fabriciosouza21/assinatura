package com.globo.pagamento.renovacao;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import org.springframework.stereotype.Component;

/**
 * Scheduler que cobra as tentativas de renovacao prontas para cobranca.
 *
 * <p>Para cada tentativa elegivel, carrega o pagamento da renovacao correspondente, cria a cobranca
 * no gateway e persiste o {@code paymentId} devolvido na tentativa.
 */
@Component
public class CobrancaRenovacaoScheduler {

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
   * Cobra as tentativas prontas.
   *
   * <p>Itera sobre as tentativas elegiveis, resolve o pagamento da renovacao, cria a cobranca no
   * gateway e persiste o {@code paymentId} devolvido na tentativa correspondente.
   */
  public void cobrar() {
    for (TentativaCobranca tentativa : tentativaCobrancaRepository.buscarProntasParaCobrar()) {
      PagamentoRenovacao pagamento =
          pagamentoRenovacaoRepository.findByRenovacaoId(tentativa.getRenovacaoId()).orElseThrow();
      CobrancaCriada cobranca =
          gateway.criarCobrancaRenovacao(
              tentativa.getRenovacaoId(), tentativa.getNumero(), pagamento.getValor());
      tentativa.registrarCobranca(cobranca.paymentId());
      tentativaCobrancaRepository.save(tentativa);
    }
  }
}
