package com.globo.pagamento.cobranca;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de consulta de cobranca exposto em {@code /cobrancas}.
 *
 * <p>Permite recuperar a correlacao entre uma assinatura e a sua cobranca no gateway de pagamento,
 * retornando o {@code paymentId} e o {@code status} atual do ciclo de vida.
 */
@RestController
@RequestMapping("/cobrancas")
public class CobrancaController {

  private final ConsultarCobranca consultarCobranca;

  /**
   * Cria o controller com a query de consulta de cobranca.
   *
   * @param consultarCobranca query de consulta de cobranca
   */
  public CobrancaController(ConsultarCobranca consultarCobranca) {
    this.consultarCobranca = consultarCobranca;
  }

  /**
   * Consulta a cobranca correlacionada a uma assinatura pelo uuid publico.
   *
   * @param assinaturaId uuid publico da assinatura
   * @return a representacao da cobranca, com status 200 OK
   */
  @GetMapping("/{assinaturaId}")
  public CobrancaResponse consultar(@PathVariable String assinaturaId) {
    return consultarCobranca.executar(assinaturaId);
  }
}
