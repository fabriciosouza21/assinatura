package com.globo.assinatura.assinatura;

/**
 * Dados enviados na solicitacao de uma assinatura.
 *
 * @param usuarioId uuid publico do usuario que solicita a assinatura
 * @param plano plano contratado
 */
public record AssinaturaRequest(String usuarioId, Plano plano) {}
