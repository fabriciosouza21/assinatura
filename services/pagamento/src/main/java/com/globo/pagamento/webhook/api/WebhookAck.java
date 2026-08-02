package com.globo.pagamento.webhook.api;

import java.util.UUID;

/**
 * Confirmacao de recebimento devolvida ao gateway apos o processamento.
 *
 * @param received confirma recebimento e processamento
 * @param eventId echo do {@code X-Mock-Event-Id}, para correlacao
 */
record WebhookAck(boolean received, UUID eventId) {}
