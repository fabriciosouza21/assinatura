package com.globo.assinatura.consulta.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.ConsultarAssinatura;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Testes HTTP da consulta de assinatura. */
@WebMvcTest(
    controllers = ConsultaAssinaturaController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@Import({ConsultaExceptionHandler.class})
class ConsultaAssinaturaControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ConsultarAssinatura consultarAssinatura;

  private static Authentication cliente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", "usuario-uuid", "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  @Test
  @DisplayName("Deve consultar assinatura por uuid e retornar 200")
  void deveConsultarAssinaturaPorUuidRetornandoOk() throws Exception {
    when(consultarAssinatura.executar(any(), any()))
        .thenReturn(
            new AssinaturaResponse(
                "assinatura-uuid",
                "usuario-uuid",
                Plano.PREMIUM,
                null,
                null,
                StatusAssinatura.AGUARDANDO_PAGAMENTO,
                null,
                null,
                null,
                false));

    mockMvc
        .perform(get("/assinaturas/assinatura-uuid").with(authentication(cliente())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("assinatura-uuid"))
        .andExpect(jsonPath("$.usuarioId").value("usuario-uuid"));
  }

  @Test
  @DisplayName("Deve retornar a proxima renovacao em datetime ISO-8601 com ciclos como data")
  void deveRetornarProximaRenovacaoEmDatetimeIso8601() throws Exception {
    when(consultarAssinatura.executar(any(), any()))
        .thenReturn(
            new AssinaturaResponse(
                "assinatura-uuid",
                "usuario-uuid",
                Plano.PREMIUM,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 9, 2),
                StatusAssinatura.ATIVA,
                LocalDate.of(2026, 8, 2),
                LocalDate.of(2026, 9, 2),
                Instant.parse("2026-09-02T12:00:00Z"),
                true));

    mockMvc
        .perform(get("/assinaturas/assinatura-uuid").with(authentication(cliente())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proximaRenovacaoEm").value("2026-09-02T12:00:00Z"))
        .andExpect(jsonPath("$.fimCiclo").value("2026-09-02"))
        .andExpect(jsonPath("$.inicioCiclo").value("2026-08-02"));
  }

  @Test
  @DisplayName("Deve retornar 404 ao consultar assinatura inexistente")
  void deveRetornarNotFoundAoConsultarAssinaturaInexistente() throws Exception {
    when(consultarAssinatura.executar(any(), any()))
        .thenThrow(new AssinaturaNaoEncontradaException());

    mockMvc
        .perform(get("/assinaturas/uuid-inexistente").with(authentication(cliente())))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 403 ao consultar assinatura de outro usuario")
  void deveRetornarForbiddenAoConsultarAssinaturaDeOutroUsuario() throws Exception {
    when(consultarAssinatura.executar(any(), any())).thenThrow(new AcessoNegadoException());

    mockMvc
        .perform(get("/assinaturas/assinatura-alheia").with(authentication(cliente())))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
  }
}
