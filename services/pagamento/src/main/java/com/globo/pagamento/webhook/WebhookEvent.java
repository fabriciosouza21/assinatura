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
   * Extrai a referencia externa correlacionada a cobranca.
   *
   * <p>E o {@code renovacaoId} quando a cobranca pertence a uma renovacao e o {@code assinaturaId}
   * quando pertence a uma adesao: e por ele que o webhook decide qual fluxo seguir.
   *
   * @return uuid da referencia externa
   */
  UUID externalReference() {
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
