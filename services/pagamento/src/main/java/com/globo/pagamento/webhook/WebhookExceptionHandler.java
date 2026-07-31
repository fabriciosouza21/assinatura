package com.globo.pagamento.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratamento de excecoes do endpoint de webhook, mapeando-as para os codigos HTTP do contrato
 * ({@code 401} para assinatura invalida, {@code 503} para falha de publicacao e {@code 500} para
 * excecoes nao tratadas).
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

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
   * Trata a falha de publicacao ou de consulta ao gateway, registrando-a em nivel ERROR por ser uma
   * falha de sistema que deve disparar alertas.
   *
   * @param e excecao lancada
   * @return resposta {@code 503} com o codigo do erro
   */
  @ExceptionHandler(PublicacaoIndisponivelException.class)
  public ResponseEntity<Erro> handleIndisponivel(PublicacaoIndisponivelException e) {
    log.error("Falha de publicacao ou consulta ao gateway", e);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Erro(e.getMessage()));
  }

  /**
   * Trata excecoes nao tratadas que escapam dos handlers especificos, registrando-as em nivel ERROR
   * com o stack completo antes de devolver {@code 500}.
   *
   * @param e excecao nao tratada
   * @return resposta {@code 500} com o codigo do erro
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Erro> handleNaoTratada(Exception e) {
    log.error("Excecao nao tratada no processamento do webhook", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Erro("erro_interno"));
  }
}
