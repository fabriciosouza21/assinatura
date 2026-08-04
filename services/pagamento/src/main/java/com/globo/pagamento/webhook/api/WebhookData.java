package com.globo.pagamento.webhook.api;

import java.util.UUID;

/**
 * Dados do pagamento contidos na notificacao do gateway.
 *
 * @param paymentId identificador da cobranca no gateway
 * @param externalReference uuid da referencia externa correlacionada: o {@code assinaturaId} numa
 *     adesao, o {@code renovacaoId} numa renovacao
 */
record WebhookData(UUID paymentId, UUID externalReference) {}
