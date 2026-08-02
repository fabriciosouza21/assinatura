package com.globo.assinatura.cancelamento.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.assinatura.assinatura.AcessoNegadoException;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.cancelamento.CancelarAssinatura;
import com.globo.assinatura.security.UsuarioAutenticado;
import com.globo.assinatura.web.ProblemExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = CancelamentoController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = {"com\\.globo\\.assinatura\\.security\\..*"}))
@Import(ProblemExceptionHandler.class)
class CancelamentoControllerTest {

  private static final String ASSINATURA_UUID = "11111111-1111-1111-1111-111111111111";
  private static final String USUARIO_UUID = "550e8400-e29b-41d4-a716-446655440000";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CancelarAssinatura cancelarAssinatura;

  @Test
  @DisplayName("Deve cancelar assinatura autenticada e retornar seu estado")
  void deveCancelarAssinaturaAutenticadaRetornandoSeuEstado() throws Exception {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", USUARIO_UUID, "ROLE_CLIENT");
    Authentication cliente =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
    CancelamentoResponse resposta =
        new CancelamentoResponse(
            ASSINATURA_UUID, StatusAssinatura.ATIVA, false, LocalDate.of(2026, 2, 1));
    when(cancelarAssinatura.executar(ASSINATURA_UUID, principal)).thenReturn(resposta);

    mockMvc
        .perform(
            post("/assinaturas/{uuid}/cancelamento", ASSINATURA_UUID)
                .with(authentication(cliente))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ASSINATURA_UUID))
        .andExpect(jsonPath("$.status").value("ATIVA"))
        .andExpect(jsonPath("$.renovacaoAutomatica").value(false))
        .andExpect(jsonPath("$.acessoAte").value("2026-02-01"));

    verify(cancelarAssinatura).executar(ASSINATURA_UUID, principal);
  }

  @Test
  @DisplayName("Deve retornar 404 ao cancelar assinatura inexistente")
  void deveRetornarNotFoundAoCancelarAssinaturaInexistente() throws Exception {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", USUARIO_UUID, "ROLE_CLIENT");
    Authentication cliente =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
    when(cancelarAssinatura.executar(anyString(), any(UsuarioAutenticado.class)))
        .thenThrow(new AssinaturaNaoEncontradaException());

    mockMvc
        .perform(
            post("/assinaturas/{uuid}/cancelamento", ASSINATURA_UUID)
                .with(authentication(cliente))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 403 ao cancelar assinatura de outro usuario")
  void deveRetornarForbiddenAoCancelarAssinaturaDeOutroUsuario() throws Exception {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", USUARIO_UUID, "ROLE_CLIENT");
    Authentication cliente =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
    when(cancelarAssinatura.executar(anyString(), any(UsuarioAutenticado.class)))
        .thenThrow(new AcessoNegadoException());

    mockMvc
        .perform(
            post("/assinaturas/{uuid}/cancelamento", ASSINATURA_UUID)
                .with(authentication(cliente))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 400 ao cancelar assinatura com uuid malformado")
  void deveRetornarBadRequestAoCancelarAssinaturaComUuidMalformado() throws Exception {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", USUARIO_UUID, "ROLE_CLIENT");
    Authentication cliente =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.role())));

    mockMvc
        .perform(
            post("/assinaturas/nao-e-uuid/cancelamento").with(authentication(cliente)).with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("uuid"))
        .andExpect(jsonPath("$.errors[0].motivo").value("deve ser um uuid valido"));

    verifyNoInteractions(cancelarAssinatura);
  }
}
