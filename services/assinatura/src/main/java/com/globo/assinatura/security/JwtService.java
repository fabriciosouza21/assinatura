package com.globo.assinatura.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Servico de emissao e validacao de tokens JWT (HS256). O segredo de assinatura e o tempo de
 * expiracao sao definidos por variaveis de ambiente.
 */
@Service
public class JwtService {

  private static final Logger log = LoggerFactory.getLogger(JwtService.class);
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_USUARIO_ID = "usuarioId";

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
   * <p>O uuid do usuario de dominio viaja como claim {@code usuarioId}, permitindo identificar o
   * dono dos recursos a cada requisicao sem consultar o banco.
   *
   * @param username identificador de login do usuario (subject)
   * @param role papel atribuido ao usuario, armazenado como claim {@code role}
   * @param usuarioId uuid publico do usuario de dominio, ou {@code null} para credenciais sem
   *     usuario de dominio ligado, como o administrador
   * @return o token compactado e assinado
   */
  public String generateToken(String username, String role, String usuarioId) {
    Instant now = Instant.now();
    var builder =
        Jwts.builder()
            .subject(username)
            .claim(CLAIM_ROLE, role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(expirationMillis)));
    if (usuarioId != null) {
      builder.claim(CLAIM_USUARIO_ID, usuarioId);
    }
    return builder.signWith(key).compact();
  }

  /**
   * Extrai a identidade contida em um token, validando assinatura, estrutura e expiracao.
   *
   * <p>Faz um unico parse do token e devolve as claims de identidade de uma vez. Tokens expirados,
   * adulterados ou malformados sao rejeitados sem propagar excecao ao chamador.
   *
   * @param token token JWT compactado
   * @return a identidade contida no token, ou {@code Optional.empty()} se o token nao for valido
   */
  public Optional<UsuarioAutenticado> extrairPrincipal(String token) {
    try {
      Claims claims = parse(token);
      return Optional.of(
          new UsuarioAutenticado(
              claims.getSubject(),
              claims.get(CLAIM_USUARIO_ID, String.class),
              claims.get(CLAIM_ROLE, String.class)));
    } catch (ExpiredJwtException e) {
      log.atDebug()
          .addKeyValue("event", "jwt_token_rejeitado")
          .addKeyValue("reasonCode", "jwt_expirado")
          .log("Token JWT rejeitado");
      return Optional.empty();
    } catch (JwtException | IllegalArgumentException e) {
      log.atDebug()
          .addKeyValue("event", "jwt_token_rejeitado")
          .addKeyValue("reasonCode", "jwt_invalido")
          .log("Token JWT rejeitado");
      return Optional.empty();
    }
  }

  private Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
