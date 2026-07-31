package com.globo.pagamento.gateway;

import java.math.BigDecimal;

/**
 * Corpo da resposta da consulta de cobranca devolvida pelo gateway de pagamento.
 *
 * @param id identificador da cobranca no gateway
 * @param externalReference referencia externa, o identificador da assinatura
 * @param amount valor em reais
 * @param currency moeda da cobranca
 * @param paymentMethod meio de pagamento
 * @param status status oficial da cobranca no gateway
 */
record PaymentResponse(
    String id,
    String externalReference,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String status) {}
