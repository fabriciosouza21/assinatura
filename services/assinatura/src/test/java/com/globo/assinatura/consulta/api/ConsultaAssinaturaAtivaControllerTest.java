package com.globo.assinatura.consulta.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.ConsultarAssinaturaAtiva;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import java.util.List;
import java.util.Optional;
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

/** Testes HTTP da consulta da assinatura ativa. */
@WebMvcTest(
    controllers = ConsultaAssinaturaAtivaController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@Import(ConsultaExceptionHandler.class)
class ConsultaAssinaturaAtivaControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ConsultarAssinaturaAtiva consultarAssinaturaAtiva;

  private static final String USUARIO_ID = "usuario-ativa-uuid";

  private static Authentication cliente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", USUARIO_ID, "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  private static Authentication administrador() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("admin", null, UsuarioAutenticado.ROLE_ADMIN);
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  @Test
  @DisplayName("Deve retornar 404 quando nao ha assinatura ativa")
  void deveRetornarNotFoundQuandoNaoHaAssinaturaAtiva() throws Exception {
    when(consultarAssinaturaAtiva.executar(USUARIO_ID)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/assinaturas/ativa").with(authentication(cliente())))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 200 quando ha assinatura ativa")
  void deveRetornarOkQuandoHaAssinaturaAtiva() throws Exception {
    when(consultarAssinaturaAtiva.executar(USUARIO_ID))
        .thenReturn(
            Optional.of(
                new AssinaturaResponse(
                    "assinatura-uuid",
                    USUARIO_ID,
                    Plano.PREMIUM,
                    null,
                    null,
                    StatusAssinatura.ATIVA,
                    null,
                    null,
                    null,
                    false)));

    mockMvc
        .perform(get("/assinaturas/ativa").with(authentication(cliente())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("assinatura-uuid"))
        .andExpect(jsonPath("$.usuarioId").value(USUARIO_ID));
  }

  @Test
  @DisplayName("Deve retornar 403 para administrador sem usuario de dominio")
  void deveRetornarForbiddenParaAdministrador() throws Exception {
    mockMvc
        .perform(get("/assinaturas/ativa").with(authentication(administrador())))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));

    verify(consultarAssinaturaAtiva, never()).executar(org.mockito.ArgumentMatchers.any());
  }
}
