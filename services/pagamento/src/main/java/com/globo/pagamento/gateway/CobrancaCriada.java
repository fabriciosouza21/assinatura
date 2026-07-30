package com.globo.pagamento.gateway;

/**
 * Resultado da criacao de uma cobranca no gateway de pagamento.
 *
 * @param paymentId identificador da cobranca no gateway
 * @param status status inicial retornado pelo gateway
 */
public record CobrancaCriada(String paymentId, String status) {}
