package com.globo.pagamento.webhook;

import java.util.UUID;

/**
 * Corpo da notificacao enviada pelo gateway de pagamento.
 *
 * @param id identificador unico do evento (= {@code X-Mock-Event-Id})
 * @param type tipo da notificacao
 * @param data dados do pagamento notificado
 */
record WebhookEvent(UUID id, String type, WebhookData data) {

  /**
   * Extrai o identificador da assinatura alvo da notificacao.
   *
   * @return uuid da assinatura (= {@code externalReference})
   */
  UUID assinaturaId() {
    return data.externalReference();
  }

  /**
   * Extrai o identificador da cobranca no gateway.
   *
   * @return payment id da cobranca
   */
  UUID paymentId() {
    return data.paymentId();
  }
}
