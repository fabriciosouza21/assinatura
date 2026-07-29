package com.globo.assinatura.auth;

/** Credenciais enviadas no login. */
public record LoginRequest(String username, String password) {}
