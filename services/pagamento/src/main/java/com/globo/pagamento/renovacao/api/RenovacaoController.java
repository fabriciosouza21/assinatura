package com.globo.pagamento.renovacao.api;

import com.globo.pagamento.renovacao.ConsultarRenovacao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de consulta de renovacao exposto em {@code /renovacoes}.
 *
 * <p>Permite recuperar a correlacao entre uma assinatura e a sua cobranca de renovacao mais recente
 * no gateway de pagamento, retornando o {@code paymentId} e o status da tentativa corrente.
 */
@RestController
@RequestMapping("/renovacoes")
public class RenovacaoController {

  private final ConsultarRenovacao consultarRenovacao;

  /**
   * Cria o controller com a query de consulta de renovacao.
   *
   * @param consultarRenovacao query de consulta de renovacao
   */
  public RenovacaoController(ConsultarRenovacao consultarRenovacao) {
    this.consultarRenovacao = consultarRenovacao;
  }

  /**
   * Consulta o pagamento de renovacao mais recente de uma assinatura pelo uuid publico.
   *
   * @param assinaturaId uuid publico da assinatura
   * @return a representacao da renovacao, com status 200 OK
   */
  @GetMapping("/{assinaturaId}")
  public RenovacaoResponse consultar(@PathVariable String assinaturaId) {
    return consultarRenovacao.executar(assinaturaId);
  }
}
