package com.globo.assinatura.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.security.JwtService;
import com.globo.assinatura.user.User;
import com.globo.assinatura.user.UserRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Testes do {@link AuthService} focados no login de cliente: emitem o token real via {@link
 * JwtService} e verificam as claims decodificadas (subject = email, role = ROLE_CLIENT, usuarioId =
 * uuid publico do usuario de dominio).
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthServiceTest {

  private static final String SECRET = "segredo-suficientemente-longo-para-hs256-aaaa-bbbb";

  @Mock private UserRepository userRepository;
  @Mock private UsuarioRepository usuarioRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService =
        new AuthService(
            userRepository,
            usuarioRepository,
            passwordEncoder,
            new JwtService(SECRET, 3600000L),
            3600000L);
  }

  private Claims claimsDoToken(String token) {
    SecretKey chave = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    return Jwts.parser().verifyWith(chave).build().parseSignedClaims(token).getPayload();
  }

  @Test
  @DisplayName("Deve emitir JWT com subject=email, role=ROLE_CLIENT e usuarioId ao logar cliente")
  void loginDeClienteEmiteTokenComClaimsDeIdentidade() {
    User cliente = new User("cliente@example.com", "hash-bcrypt", "ROLE_CLIENT", 1L);
    Usuario usuario = new Usuario("Fulano", "cliente@example.com");
    when(userRepository.findByUsername("cliente@example.com")).thenReturn(Optional.of(cliente));
    when(passwordEncoder.matches("SenhaForte1", "hash-bcrypt")).thenReturn(true);
    when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));

    LoginResponse resposta =
        authService.login(new LoginRequest("cliente@example.com", "SenhaForte1"));

    Claims claims = claimsDoToken(resposta.token());
    assertThat(claims.getSubject()).as("Subject do JWT e o email").isEqualTo("cliente@example.com");
    assertThat(claims).as("Claim role do cliente").containsEntry("role", "ROLE_CLIENT");
    assertThat(claims)
        .as("Claim usuarioId com o uuid publico do usuario de dominio")
        .containsEntry("usuarioId", usuario.getUuid());
    assertThat(resposta.tokenType()).as("Tipo do token").isEqualTo("Bearer");
  }

  @Test
  @DisplayName("Deve emitir JWT sem claim usuarioId ao logar credencial sem usuario ligado")
  void loginDeAdminEmiteTokenSemUsuarioId() {
    User admin = new User("admin", "hash-bcrypt", "ROLE_ADMIN", null);
    when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
    when(passwordEncoder.matches("admin123", "hash-bcrypt")).thenReturn(true);

    LoginResponse resposta = authService.login(new LoginRequest("admin", "admin123"));

    Claims claims = claimsDoToken(resposta.token());
    assertThat(claims).as("Claim role do admin").containsEntry("role", "ROLE_ADMIN");
    assertThat(claims)
        .as("Admin nao possui usuario de dominio ligado")
        .doesNotContainKey("usuarioId");
  }

  @Test
  @DisplayName("Deve rodar o password encoder mesmo quando o usuario nao existe")
  void loginDeUsuarioInexistenteRodaPasswordEncoder(CapturedOutput output) {
    when(userRepository.findByUsername("nao-existe@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("nao-existe@example.com", "SenhaForte1")))
        .as("Credenciais de usuario inexistente devem ser rejeitadas")
        .isInstanceOf(BadCredentialsException.class);

    verify(passwordEncoder).matches(eq("SenhaForte1"), anyString());
  }

  @Test
  @DisplayName("Deve emitir JWT sem claim usuarioId e registrar warn em credencial orfa")
  void loginComCredencialOrfaEmiteTokenSemUsuarioId(CapturedOutput output) {
    User orfao = new User("orfao@example.com", "hash-bcrypt", "ROLE_CLIENT", 99L);
    when(userRepository.findByUsername("orfao@example.com")).thenReturn(Optional.of(orfao));
    when(passwordEncoder.matches("SenhaForte1", "hash-bcrypt")).thenReturn(true);
    when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

    LoginResponse resposta =
        authService.login(new LoginRequest("orfao@example.com", "SenhaForte1"));

    Claims claims = claimsDoToken(resposta.token());
    assertThat(claims)
        .as("Credencial orfa nao recebe claim usuarioId")
        .doesNotContainKey("usuarioId");
    assertThat(output)
        .as("Warn deve sinalizar a degradacao com evento estavel")
        .contains("credencial_usuario_orfao");
  }
}
