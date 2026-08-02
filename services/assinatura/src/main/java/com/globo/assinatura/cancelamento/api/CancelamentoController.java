package com.globo.assinatura.cancelamento.api;

import com.globo.assinatura.cancelamento.CancelarAssinatura;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expõe as operações HTTP de cancelamento de assinatura. */
@RestController
@RequestMapping("/assinaturas")
public class CancelamentoController {

  private final CancelarAssinatura cancelarAssinatura;

  /**
   * Constroi o controller com o command de cancelamento injetado.
   *
   * @param cancelarAssinatura command que executa o cancelamento da assinatura
   */
  public CancelamentoController(CancelarAssinatura cancelarAssinatura) {
    this.cancelarAssinatura = cancelarAssinatura;
  }

  /**
   * Solicita o cancelamento da assinatura identificada pelo uuid.
   *
   * @param uuid uuid publico da assinatura
   * @param principal identidade extraida do token JWT
   * @return estado da assinatura apos a solicitacao de cancelamento
   */
  @PostMapping("/{uuid}/cancelamento")
  public CancelamentoResponse cancelar(
      @PathVariable UUID uuid, @AuthenticationPrincipal UsuarioAutenticado principal) {
    return cancelarAssinatura.executar(uuid.toString(), principal);
  }
}
