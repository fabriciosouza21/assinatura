package com.globo.pagamento.shared.outbox.api;

import com.globo.pagamento.shared.outbox.OutboxEventoNaoEncontradoException;
import com.globo.pagamento.shared.outbox.OutboxEventoNaoRetomavelException;
import com.globo.pagamento.shared.seguranca.AcessoNegadoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Trata as excecoes dos endpoints de recuperacao manual da outbox. */
@RestControllerAdvice(assignableTypes = OutboxFalhaController.class)
public class OutboxFalhaExceptionHandler {

  /**
   * Mapeia um evento ausente para {@code 404 Not Found}.
   *
   * @param ex excecao de evento inexistente
   * @return resposta sem corpo com status {@code 404}
   */
  @ExceptionHandler(OutboxEventoNaoEncontradoException.class)
  public ResponseEntity<Void> tratarEventoNaoEncontrado(OutboxEventoNaoEncontradoException ex) {
    return ResponseEntity.notFound().build();
  }

  /**
   * Mapeia evento fora de falha para {@code 409 Conflict}, sem alterar o evento.
   *
   * @param ex excecao de estado nao retomavel
   * @return resposta sem corpo com status {@code 409}
   */
  @ExceptionHandler(OutboxEventoNaoRetomavelException.class)
  public ResponseEntity<Void> tratarEventoNaoRetomavel(OutboxEventoNaoRetomavelException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  /**
   * Mapeia acesso sem permissao de administrador para {@code 403 Forbidden}.
   *
   * @param ex excecao de autorizacao
   * @return resposta sem corpo com status {@code 403}
   */
  @ExceptionHandler(AcessoNegadoException.class)
  public ResponseEntity<Void> tratarAcessoNegado(AcessoNegadoException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }
}
