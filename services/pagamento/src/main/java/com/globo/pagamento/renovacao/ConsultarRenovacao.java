package com.globo.pagamento.renovacao;

import com.globo.pagamento.renovacao.api.RenovacaoResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de consulta do pagamento de renovacao mais recente de uma assinatura.
 *
 * <p>Recupera a correlacao entre uma assinatura e a sua cobranca de renovacao no gateway de
 * pagamento pelo uuid publico da assinatura. Responsavel apenas por leitura.
 */
@Service
public class ConsultarRenovacao {

  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final TentativaCobrancaRepository tentativaCobrancaRepository;

  /**
   * Constroi a query com os repositorios injetados.
   *
   * @param pagamentoRenovacaoRepository repositorio de persistencia de pagamentos de renovacao
   * @param tentativaCobrancaRepository repositorio de persistencia de tentativas de cobranca
   */
  public ConsultarRenovacao(
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      TentativaCobrancaRepository tentativaCobrancaRepository) {
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.tentativaCobrancaRepository = tentativaCobrancaRepository;
  }

  /**
   * Consulta o pagamento de renovacao mais recente de uma assinatura pelo uuid publico.
   *
   * @param assinaturaId uuid publico da assinatura renovada
   * @return a representacao da renovacao, com o payment id e o status da tentativa corrente
   * @throws RenovacaoNaoEncontradaException se a assinatura ainda nao tiver renovado
   */
  @Transactional(readOnly = true)
  public RenovacaoResponse executar(String assinaturaId) {
    PagamentoRenovacao pagamento =
        pagamentoRenovacaoRepository
            .findFirstByAssinaturaIdOrderByCriadoEmDesc(assinaturaId)
            .orElseThrow(RenovacaoNaoEncontradaException::new);
    TentativaCobranca tentativa =
        tentativaCobrancaRepository
            .findFirstByRenovacaoIdOrderByNumeroDesc(pagamento.getRenovacaoId())
            .orElseThrow(RenovacaoNaoEncontradaException::new);
    return new RenovacaoResponse(
        pagamento.getAssinaturaId(),
        pagamento.getRenovacaoId(),
        pagamento.getCicloReferencia(),
        pagamento.getPlano(),
        pagamento.getValor(),
        tentativa.getNumero(),
        tentativa.getStatus(),
        tentativa.getPaymentId(),
        tentativa.getProximaTentativaEm());
  }
}
