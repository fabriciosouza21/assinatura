package com.globo.pagamento.webhook;

/**
 * Lancada quando a assinatura HMAC do webhook esta ausente ou nao bate com o corpo, indicando uma
 * notificacao nao autentica.
 */
public class WebhookInvalidoException extends RuntimeException {

  /** Construtor padrao. */
  public WebhookInvalidoException() {
    super("assinatura_hmac_invalida");
  }
}
