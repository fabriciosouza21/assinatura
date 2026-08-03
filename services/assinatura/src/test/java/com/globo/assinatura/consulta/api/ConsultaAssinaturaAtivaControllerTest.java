package com.globo.assinatura.consulta.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

  private static Authentication cliente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", "usuario-uuid", "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  @Test
  @DisplayName("Deve retornar 404 quando nao ha assinatura ativa")
  void deveRetornarNotFoundQuandoNaoHaAssinaturaAtiva() throws Exception {
    when(consultarAssinaturaAtiva.executar("usuario-uuid")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/assinaturas/ativa").with(authentication(cliente())))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }
}
