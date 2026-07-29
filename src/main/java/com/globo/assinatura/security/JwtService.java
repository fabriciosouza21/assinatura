package com.globo.assinatura.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Servico de emissao e validacao de tokens JWT (HS256). O segredo de assinatura e o tempo de
 * expiracao sao definidos por variaveis de ambiente.
 */
@Service
public class JwtService {

  private final SecretKey key;
  private final long expirationMillis;

  /**
   * Constroi o servico a partir do segredo e do tempo de expiracao configurados.
   *
   * @param secret segredo HMAC em texto plano (UTF-8)
   * @param expirationMillis tempo de vida do token, em milissegundos
   */
  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.expiration-ms}") long expirationMillis) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationMillis = expirationMillis;
  }

  /**
   * Gera um token JWT assinado para o usuario informado.
   *
   * @param username identificador do usuario (subject)
   * @param role papel atribuido ao usuario, armazenado como claim {@code role}
   * @return o token compactado e assinado
   */
  public String generateToken(String username, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(username)
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusMillis(expirationMillis)))
        .signWith(key)
        .compact();
  }

  /**
   * Extrai o subject (username) de um token.
   *
   * @param token token JWT compactado
   * @return o subject contido no token
   */
  public String extractUsername(String token) {
    return parse(token).getSubject();
  }

  /**
   * Indica se o token e valido quanto a assinatura e a estrutura.
   *
   * @param token token JWT compactado
   * @return {@code true} se o token for assinado por esta chave e estiver bem formado
   */
  public boolean isValid(String token) {
    try {
      parse(token);
      return true;
    } catch (JwtException | IllegalArgumentException ignored) {
      return false;
    }
  }

  private Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
