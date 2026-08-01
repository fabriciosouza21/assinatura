package com.globo.assinatura.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.globo.assinatura.security.JwtService;
import com.globo.assinatura.user.User;
import com.globo.assinatura.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Testes do {@link AuthService} focados no login de cliente: emitem o token real via {@link
 * JwtService} e verificam as claims decodificadas (subject = email, role = ROLE_CLIENT).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final String SECRET = "segredo-suficientemente-longo-para-hs256-aaaa-bbbb";

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            userRepository, passwordEncoder, new JwtService(SECRET, 3600000L), 3600000L);
  }

  @Test
  @DisplayName("Deve emitir JWT com subject=email e role=ROLE_CLIENT ao logar cliente")
  void loginDeClienteEmiteTokenComRoleCliente() {
    User cliente = new User("cliente@example.com", "hash-bcrypt", "ROLE_CLIENT", 1L);
    when(userRepository.findByUsername("cliente@example.com")).thenReturn(Optional.of(cliente));
    when(passwordEncoder.matches("SenhaForte1", "hash-bcrypt")).thenReturn(true);

    LoginResponse resposta =
        authService.login(new LoginRequest("cliente@example.com", "SenhaForte1"));

    SecretKey chave = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    Claims claims =
        Jwts.parser().verifyWith(chave).build().parseSignedClaims(resposta.token()).getPayload();
    assertThat(claims.getSubject()).as("Subject do JWT e o email").isEqualTo("cliente@example.com");
    assertThat(claims).as("Claim role do cliente").containsEntry("role", "ROLE_CLIENT");
    assertThat(resposta.tokenType()).as("Tipo do token").isEqualTo("Bearer");
  }
}
