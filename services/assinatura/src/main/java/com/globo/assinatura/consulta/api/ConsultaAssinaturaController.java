package com.globo.assinatura.consulta.api;

import com.globo.assinatura.consulta.ConsultarAssinatura;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller da query de assinatura exposto em {@code /assinaturas}. */
@RestController
@RequestMapping("/assinaturas")
public class ConsultaAssinaturaController {

  private final ConsultarAssinatura consultarAssinatura;

  /**
   * Cria o controller com a query de consulta.
   *
   * @param consultarAssinatura query de consulta de assinatura
   */
  public ConsultaAssinaturaController(ConsultarAssinatura consultarAssinatura) {
    this.consultarAssinatura = consultarAssinatura;
  }

  /**
   * Consulta uma assinatura do usuario autenticado pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @param principal identidade extraida do token JWT
   * @return a representacao completa da assinatura
   */
  @GetMapping("/{uuid}")
  public AssinaturaResponse consultar(
      @PathVariable String uuid, @AuthenticationPrincipal UsuarioAutenticado principal) {
    return consultarAssinatura.executar(uuid, principal);
  }
}
