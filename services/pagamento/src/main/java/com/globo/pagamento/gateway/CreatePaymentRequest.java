package com.globo.pagamento.gateway;

import java.math.BigDecimal;

/**
 * Corpo da requisicao de criacao de cobranca enviada ao gateway de pagamento.
 *
 * @param externalReference referencia externa, o identificador da assinatura
 * @param amount valor em reais, sem conversao para centavos
 * @param currency moeda da cobranca
 * @param paymentMethod meio de pagamento
 * @param notificationUrl url para notificacoes de webhook
 */
record CreatePaymentRequest(
    String externalReference,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String notificationUrl) {}
