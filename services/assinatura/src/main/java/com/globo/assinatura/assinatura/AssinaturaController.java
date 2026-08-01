package com.globo.assinatura.assinatura;

import com.globo.assinatura.security.UsuarioAutenticado;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller de assinatura exposto em {@code /assinaturas}.
 *
 * <p>Todas as rotas exigem token JWT. O dono da assinatura vem sempre do principal autenticado,
 * nunca do corpo ou da query string.
 */
@RestController
@RequestMapping("/assinaturas")
public class AssinaturaController {

  private final SolicitarAssinatura solicitarAssinatura;
  private final ConsultarAssinatura consultarAssinatura;

  /**
   * Cria o controller com o command e a query de assinatura.
   *
   * @param solicitarAssinatura command de solicitacao de assinatura
   * @param consultarAssinatura query de consulta de assinatura
   */
  public AssinaturaController(
      SolicitarAssinatura solicitarAssinatura, ConsultarAssinatura consultarAssinatura) {
    this.solicitarAssinatura = solicitarAssinatura;
    this.consultarAssinatura = consultarAssinatura;
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

  /**
   * Consulta uma assinatura do usuario autenticado pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @param principal identidade extraida do token JWT
   * @return a representacao completa da assinatura, com status 200 OK
   */
  @GetMapping("/{uuid}")
  public AssinaturaResponse consultar(
      @PathVariable String uuid, @AuthenticationPrincipal UsuarioAutenticado principal) {
    return consultarAssinatura.executar(uuid, principal);
  }
}
