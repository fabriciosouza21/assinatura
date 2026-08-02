package com.globo.pagamento.consulta.api;

import com.globo.pagamento.cobranca.CobrancaNaoEncontradaException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador das excecoes de dominio de cobranca expostas aos endpoints REST.
 *
 * <p>Converte as excecoes lancadas pela consulta em respostas HTTP com corpo vazio, evitando vazar
 * identificadores em logs.
 */
@RestControllerAdvice(assignableTypes = CobrancaController.class)
public class CobrancaExceptionHandler {

  /**
   * Mapeia {@link CobrancaNaoEncontradaException} para {@code 404 Not Found}.
   *
   * @param ex excecao lancada quando a cobranca consultada nao existe
   * @return resposta com status {@code 404} e corpo vazio
   */
  @ExceptionHandler(CobrancaNaoEncontradaException.class)
  public ResponseEntity<Void> tratarCobrancaNaoEncontrada(CobrancaNaoEncontradaException ex) {
    return ResponseEntity.notFound().build();
  }
}
