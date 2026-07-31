package com.globo.pagamento.webhook;

import java.util.UUID;

/**
 * Dados do pagamento contidos na notificacao do gateway.
 *
 * @param paymentId identificador da cobranca no gateway
 * @param externalReference uuid da assinatura correlacionada
 */
record WebhookData(UUID paymentId, UUID externalReference) {}
