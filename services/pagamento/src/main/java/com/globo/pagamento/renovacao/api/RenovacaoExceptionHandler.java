package com.globo.pagamento.renovacao.api;

import com.globo.pagamento.renovacao.RenovacaoNaoEncontradaException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador das excecoes de dominio de renovacao expostas aos endpoints REST.
 *
 * <p>Converte as excecoes lancadas pela consulta em respostas HTTP com corpo vazio, evitando vazar
 * identificadores em logs.
 */
@RestControllerAdvice(assignableTypes = RenovacaoController.class)
public class RenovacaoExceptionHandler {

  /**
   * Mapeia {@link RenovacaoNaoEncontradaException} para {@code 404 Not Found}.
   *
   * @param ex excecao lancada quando a renovacao consultada nao existe
   * @return resposta com status {@code 404} e corpo vazio
   */
  @ExceptionHandler(RenovacaoNaoEncontradaException.class)
  public ResponseEntity<Void> tratarRenovacaoNaoEncontrada(RenovacaoNaoEncontradaException ex) {
    return ResponseEntity.notFound().build();
  }
}
