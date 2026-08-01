package com.globo.assinatura.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Testes do {@link JwtAuthenticationFilter} verificando que o principal publicado no contexto de
 * seguranca carrega a identidade do token e que a authority vem da claim {@code role}.
 */
class JwtAuthenticationFilterTest {

  private static final String SECRET = "segredo-suficientemente-longo-para-hs256-aaaa-bbbb";
  private static final String USUARIO_UUID = "550e8400-e29b-41d4-a716-446655440000";

  private final JwtService jwtService = new JwtService(SECRET, 3600000L);
  private final JwtAuthenticationFilter filtro = new JwtAuthenticationFilter(jwtService);

  @AfterEach
  void limparContexto() {
    SecurityContextHolder.clearContext();
  }

  private Authentication autenticarCom(String cabecalho) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (cabecalho != null) {
      request.addHeader("Authorization", cabecalho);
    }
    FilterChain chain = new MockFilterChain();
    filtro.doFilter(request, new MockHttpServletResponse(), chain);
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  @DisplayName("Deve publicar principal com usuarioId e authority da claim role")
  void devePublicarPrincipalComAuthorityDaClaimRole() throws Exception {
    String token = jwtService.generateToken("cliente@example.com", "ROLE_CLIENT", USUARIO_UUID);

    Authentication auth = autenticarCom("Bearer " + token);

    assertThat(auth).as("Token valido deve autenticar a requisicao").isNotNull();
    assertThat(auth.getPrincipal())
        .as("Principal do contexto")
        .isEqualTo(new UsuarioAutenticado("cliente@example.com", USUARIO_UUID, "ROLE_CLIENT"));
    assertThat(auth.getAuthorities())
        .as("Authority derivada da claim role")
        .extracting(Object::toString)
        .containsExactly("ROLE_CLIENT");
  }

  @Test
  @DisplayName("Deve publicar authority de administrador para token com role ROLE_ADMIN")
  void devePublicarAuthorityDeAdministrador() throws Exception {
    String token = jwtService.generateToken("admin", UsuarioAutenticado.ROLE_ADMIN, null);

    Authentication auth = autenticarCom("Bearer " + token);

    assertThat(auth.getAuthorities())
        .as("Authority de administrador")
        .extracting(Object::toString)
        .containsExactly(UsuarioAutenticado.ROLE_ADMIN);
  }

  @Test
  @DisplayName("Deve seguir a cadeia sem autenticar quando nao ha cabecalho Authorization")
  void deveSeguirSemAutenticarSemCabecalho() throws Exception {
    assertThat(autenticarCom(null)).as("Requisicao sem cabecalho nao deve autenticar").isNull();
  }

  @Test
  @DisplayName("Deve seguir a cadeia sem autenticar quando o token e invalido")
  void deveSeguirSemAutenticarComTokenInvalido() throws Exception {
    assertThat(autenticarCom("Bearer nao-e-um-jwt"))
        .as("Token invalido nao deve autenticar")
        .isNull();
  }
}
