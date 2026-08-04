package com.globo.assinatura.adesao.api;

import com.globo.assinatura.adesao.AssinaturaAbertaException;
import com.globo.assinatura.adesao.UsuarioNaoEncontradoException;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador das excecoes de dominio de assinatura expostas aos endpoints REST.
 *
 * <p>Converte as excecoes lancadas pelo command e pela query em respostas HTTP com corpo vazio,
 * evitando vazar identificadores em logs.
 */
@RestControllerAdvice(assignableTypes = AdesaoController.class)
public class AdesaoExceptionHandler {

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

  /**
   * Mapeia {@link AssinaturaAbertaException} para {@code 409 Conflict}.
   *
   * @param ex excecao lancada quando o usuario ja possui assinatura aberta
   * @return resposta com status {@code 409} e corpo vazio
   */
  @ExceptionHandler(AssinaturaAbertaException.class)
  public ResponseEntity<Void> tratarAssinaturaAberta(AssinaturaAbertaException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  /**
   * Mapeia {@link AcessoNegadoException} para {@code 403 Forbidden}.
   *
   * @param ex excecao lancada quando o principal nao pode solicitar a assinatura
   * @return resposta com status {@code 403} e corpo vazio
   */
  @ExceptionHandler(AcessoNegadoException.class)
  public ResponseEntity<Void> tratarAcessoNegado(AcessoNegadoException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }
}
