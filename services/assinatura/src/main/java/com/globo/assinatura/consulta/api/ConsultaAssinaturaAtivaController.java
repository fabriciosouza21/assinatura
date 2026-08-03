package com.globo.assinatura.consulta.api;

import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.consulta.ConsultarAssinaturaAtiva;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller da query da assinatura ativa exposto em {@code /assinaturas}. */
@RestController
@RequestMapping("/assinaturas")
public class ConsultaAssinaturaAtivaController {

  private final ConsultarAssinaturaAtiva consultarAssinaturaAtiva;

  /**
   * Cria o controller com a query de consulta da assinatura ativa.
   *
   * @param consultarAssinaturaAtiva query de consulta da assinatura ativa
   */
  public ConsultaAssinaturaAtivaController(ConsultarAssinaturaAtiva consultarAssinaturaAtiva) {
    this.consultarAssinaturaAtiva = consultarAssinaturaAtiva;
  }

  /**
   * Consulta a assinatura ativa do usuario autenticado.
   *
   * @param principal identidade extraida do token JWT
   * @return a representacao da assinatura ativa do usuario
   * @throws AssinaturaNaoEncontradaException se o usuario nao tiver assinatura ativa
   */
  @GetMapping("/ativa")
  public AssinaturaResponse consultarAtiva(@AuthenticationPrincipal UsuarioAutenticado principal) {
    return consultarAssinaturaAtiva
        .executar(principal.usuarioId())
        .orElseThrow(AssinaturaNaoEncontradaException::new);
  }
}
