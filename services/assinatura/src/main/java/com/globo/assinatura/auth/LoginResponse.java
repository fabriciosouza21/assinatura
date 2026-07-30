package com.globo.assinatura.auth;

/** Token JWT devolvido apos um login bem-sucedido. */
public record LoginResponse(String token, String tokenType, long expiresInMs) {}
