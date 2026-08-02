package com.globo.assinatura.adesao.api;

import com.globo.assinatura.adesao.SolicitarAssinatura;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller de adesao exposto em {@code /assinaturas}.
 *
 * <p>Todas as rotas exigem token JWT. O dono da assinatura vem sempre do principal autenticado,
 * nunca do corpo ou da query string.
 */
@RestController
@RequestMapping("/assinaturas")
public class AdesaoController {

  private final SolicitarAssinatura solicitarAssinatura;

  /**
   * Cria o controller com o command de solicitacao de assinatura.
   *
   * @param solicitarAssinatura command de solicitacao de assinatura
   */
  public AdesaoController(SolicitarAssinatura solicitarAssinatura) {
    this.solicitarAssinatura = solicitarAssinatura;
  }

  /**
   * Solicita uma assinatura para o usuario autenticado.
   *
   * @param request dados da solicitacao
   * @param principal identidade extraida do token JWT
   * @return a assinatura criada, com status 202 Accepted
   * @throws AcessoNegadoException se o token nao estiver ligado a um usuario de dominio
   */
  @PostMapping
  public ResponseEntity<AssinaturaCriadaResponse> solicitar(
      @Valid @RequestBody AssinaturaRequest request,
      @AuthenticationPrincipal UsuarioAutenticado principal) {
    if (principal.usuarioId() == null) {
      throw new AcessoNegadoException();
    }
    Assinatura assinatura = solicitarAssinatura.executar(principal.usuarioId(), request.plano());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new AssinaturaCriadaResponse(assinatura.getUuid(), assinatura.getStatus()));
  }
}
