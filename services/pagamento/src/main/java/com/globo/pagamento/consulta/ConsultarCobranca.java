package com.globo.pagamento.consulta;

import com.globo.pagamento.cobranca.Cobranca;
import com.globo.pagamento.cobranca.CobrancaNaoEncontradaException;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.consulta.api.CobrancaResponse;
import org.springframework.stereotype.Service;

/**
 * Query de consulta de cobranca.
 *
 * <p>Recupera a correlacao de cobranca de uma assinatura pelo uuid publico da assinatura.
 * Responsavel apenas por leitura.
 */
@Service
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
    Cobranca cobranca =
        cobrancaRepository
            .findByAssinaturaUuid(uuid)
            .orElseThrow(CobrancaNaoEncontradaException::new);
    return new CobrancaResponse(cobranca.getPaymentId(), cobranca.getStatus());
  }
}
