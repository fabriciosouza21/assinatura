package com.globo.pagamento.cobranca;

/**
 * Representacao de uma cobranca retornada na consulta.
 *
 * @param paymentId identificador da cobranca no gateway de pagamento
 * @param status status do ciclo de vida da cobranca
 */
public record CobrancaResponse(String paymentId, StatusCobranca status) {}
