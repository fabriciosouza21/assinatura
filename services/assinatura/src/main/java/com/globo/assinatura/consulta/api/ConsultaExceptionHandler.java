package com.globo.assinatura.consulta.api;

import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Trata as excecoes das queries de leitura de assinatura. */
@RestControllerAdvice(
    assignableTypes = {ConsultaAssinaturaController.class, ListaAssinaturasController.class})
public class ConsultaExceptionHandler {

  /**
   * Mapeia uma assinatura ausente para {@code 404 Not Found}.
   *
   * @param ex excecao de assinatura inexistente
   * @return resposta sem corpo com status {@code 404}
   */
  @ExceptionHandler(AssinaturaNaoEncontradaException.class)
  public ResponseEntity<Void> tratarAssinaturaNaoEncontrada(AssinaturaNaoEncontradaException ex) {
    return ResponseEntity.notFound().build();
  }

  /**
   * Mapeia acesso a assinatura de terceiro para {@code 403 Forbidden}.
   *
   * @param ex excecao de autorizacao
   * @return resposta sem corpo com status {@code 403}
   */
  @ExceptionHandler(AcessoNegadoException.class)
  public ResponseEntity<Void> tratarAcessoNegado(AcessoNegadoException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }
}
