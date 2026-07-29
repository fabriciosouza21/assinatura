package com.globo.assinatura.auth;

public record LoginResponse(String token, String tokenType, long expiresInMs) {
}
