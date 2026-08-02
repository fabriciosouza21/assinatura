package com.globo.assinatura.auth.api;

import com.globo.assinatura.auth.AutenticarUsuario;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller de autenticação exposto em {@code /auth}. */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AutenticarUsuario authService;

  /**
   * Cria o controller com o serviço de autenticação.
   *
   * @param authService o serviço de autenticação
   */
  public AuthController(AutenticarUsuario authService) {
    this.authService = authService;
  }

  /**
   * Autentica um usuário e devolve um token JWT.
   *
   * @param request credenciais de login
   * @return o token gerado, com tipo e tempo de expiração
   */
  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }
}
