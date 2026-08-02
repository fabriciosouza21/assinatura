package com.globo.assinatura.auth.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globo.assinatura.auth.AutenticarUsuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes slice do {@link AuthController}, isolando o comportamento do controller do resto da
 * aplicacao (incluindo a cadeia de filtros do Spring Security).
 *
 * <p>O {@code excludeFilter} por regex impede que o {@code SecurityConfig} de producao seja
 * carregado no slice, e a exclusao de {@link ServletWebSecurityAutoConfiguration} remove a cadeia
 * default que rejeitaria o POST com 403. O handler de credenciais invalidas e carregado via {@link
 * Import} para converter {@link BadCredentialsException} em {@code 401}.
 */
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@ImportAutoConfiguration(exclude = {ServletWebSecurityAutoConfiguration.class})
@Import(BadCredentialsExceptionHandler.class)
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private AutenticarUsuario authService;

  @Test
  @DisplayName("Deve retornar 401 com corpo vazio ao receber credenciais invalidas")
  void loginComCredenciaisInvalidasRetornaUnauthorized() throws Exception {
    LoginRequest credenciais = new LoginRequest("cliente@example.com", "errada");
    when(authService.login(credenciais))
        .thenThrow(new BadCredentialsException("Credenciais invalidas"));

    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(credenciais)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(""));
  }
}
