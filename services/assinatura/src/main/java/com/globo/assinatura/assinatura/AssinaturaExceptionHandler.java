package com.globo.assinatura.assinatura;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador das excecoes de dominio de assinatura expostas aos endpoints REST.
 *
 * <p>Converte as excecoes lancadas pelo command e pela query em respostas HTTP com corpo vazio,
 * evitando vazar identificadores em logs.
 */
@RestControllerAdvice
public class AssinaturaExceptionHandler {

  /**
   * Mapeia {@link UsuarioNaoEncontradoException} para {@code 404 Not Found}.
   *
   * @param ex excecao lancada quando o usuario informado nao existe
   * @return resposta com status {@code 404} e corpo vazio
   */
  @ExceptionHandler(UsuarioNaoEncontradoException.class)
  public ResponseEntity<Void> tratarUsuarioNaoEncontrado(UsuarioNaoEncontradoException ex) {
    return ResponseEntity.notFound().build();
  }
}
