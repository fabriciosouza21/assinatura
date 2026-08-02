package com.globo.assinatura.cancelamento.api;

import com.globo.assinatura.assinatura.AcessoNegadoException;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Trata as excecoes da capacidade de cancelamento expostas pela API HTTP. */
@RestControllerAdvice(assignableTypes = CancelamentoController.class)
public class CancelamentoExceptionHandler {

  /**
   * Mapeia assinatura nao encontrada para {@code 404 Not Found}.
   *
   * @param ex excecao lancada quando a assinatura ou seu dono nao existem
   * @return resposta com status {@code 404} e corpo vazio
   */
  @ExceptionHandler(AssinaturaNaoEncontradaException.class)
  public ResponseEntity<Void> tratarAssinaturaNaoEncontrada(AssinaturaNaoEncontradaException ex) {
    return ResponseEntity.notFound().build();
  }

  /**
   * Mapeia acesso negado para {@code 403 Forbidden}.
   *
   * @param ex excecao lancada quando o solicitante nao e o dono da assinatura
   * @return resposta com status {@code 403} e corpo vazio
   */
  @ExceptionHandler(AcessoNegadoException.class)
  public ResponseEntity<Void> tratarAcessoNegado(AcessoNegadoException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }
}
