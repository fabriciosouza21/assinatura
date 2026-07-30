package com.globo.assinatura.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador de credenciais invalidas exposto aos endpoints REST.
 *
 * <p>Converte {@link BadCredentialsException}, lancada pelo {@link AuthService} quando o usuario
 * nao existe ou a senha nao confere, em uma resposta HTTP {@code 401 Unauthorized}. O corpo da
 * resposta e mantido vazio para nao revelar qual das duas condicoes ocorreu, evitando enumeracao de
 * usuarios.
 */
@RestControllerAdvice
public class BadCredentialsExceptionHandler {

  /**
   * Mapeia {@link BadCredentialsException} para {@code 401 Unauthorized}.
   *
   * @param ex excecao lancada ao receber credenciais invalidas
   * @return resposta com status {@code 401} e corpo vazio
   */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<Void> tratarCredenciaisInvalidas(BadCredentialsException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }
}
