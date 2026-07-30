package com.globo.assinatura.assinatura;

/**
 * Resposta da solicitacao de assinatura aceita.
 *
 * @param id uuid publico da assinatura criada
 * @param status status da assinatura criada
 */
public record AssinaturaCriadaResponse(String id, StatusAssinatura status) {}
