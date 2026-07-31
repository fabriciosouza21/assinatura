package com.globo.pagamento.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento de excecoes do endpoint de webhook, mapeando-as para os codigos HTTP do contrato
 * ({@code 401} para assinatura invalida e {@code 503} para falha de publicacao).
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

  /**
   * Trata a assinatura HMAC invalida.
   *
   * @param e excecao lancada
   * @return resposta {@code 401} com o codigo do erro
   */
  @ExceptionHandler(WebhookInvalidoException.class)
  public ResponseEntity<Erro> handleInvalido(WebhookInvalidoException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Erro(e.getMessage()));
  }

  /**
   * Trata a falha de publicacao ou de consulta ao gateway.
   *
   * @param e excecao lancada
   * @return resposta {@code 503} com o codigo do erro
   */
  @ExceptionHandler(PublicacaoIndisponivelException.class)
  public ResponseEntity<Erro> handleIndisponivel(PublicacaoIndisponivelException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Erro(e.getMessage()));
  }
}
