package com.globo.assinatura.auth.api;

/** Credenciais enviadas no login. */
public record LoginRequest(String username, String password) {}
