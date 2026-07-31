package com.globo.pagamento.cobranca;

/**
 * Query de consulta de cobranca.
 *
 * <p>Recupera a correlacao de cobranca de uma assinatura pelo uuid publico da assinatura.
 * Responsavel apenas por leitura.
 */
public class ConsultarCobranca {

  private final CobrancaRepository cobrancaRepository;

  /**
   * Constroi a query com o repositorio injetado.
   *
   * @param cobrancaRepository repositorio de persistencia de cobrancas
   */
  public ConsultarCobranca(CobrancaRepository cobrancaRepository) {
    this.cobrancaRepository = cobrancaRepository;
  }

  /**
   * Consulta a cobranca correlacionada a uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a representacao da cobranca, com o payment id e o status do gateway
   */
  public CobrancaResponse executar(String uuid) {
    Cobranca cobranca = cobrancaRepository.findByAssinaturaUuid(uuid).orElseThrow();
    return new CobrancaResponse(cobranca.getPaymentId(), cobranca.getStatus());
  }
}
