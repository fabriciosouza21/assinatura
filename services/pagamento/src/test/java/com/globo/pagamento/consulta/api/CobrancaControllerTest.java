package com.globo.pagamento.consulta.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.pagamento.cobranca.CobrancaNaoEncontradaException;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.consulta.ConsultarCobranca;
import com.globo.pagamento.shared.seguranca.JwtService;
import com.globo.pagamento.shared.seguranca.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste de fatia web do {@link CobrancaController}.
 *
 * <p>Verifica o mapeamento HTTP da consulta de cobranca: {@code 200} com o corpo contendo o {@code
 * paymentId} e o {@code status} quando existe cobranca para a assinatura, sem exigir autenticacao.
 */
@WebMvcTest(controllers = CobrancaController.class)
@Import(SecurityConfig.class)
class CobrancaControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ConsultarCobranca consultarCobranca;
  @MockitoBean private JwtService jwtService;

  @Test
  @DisplayName("Deve retornar 200 com paymentId e status quando a cobranca existe")
  void deveRetornar200ComDadosQuandoCobrancaExiste() throws Exception {
    when(consultarCobranca.executar("assinatura-uuid"))
        .thenReturn(new CobrancaResponse("pay-123", StatusCobranca.APPROVED));

    mockMvc
        .perform(get("/cobrancas/{assinaturaId}", "assinatura-uuid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value("pay-123"))
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  @DisplayName("Deve retornar 404 com corpo vazio quando a cobranca nao existe")
  void deveRetornar404ComCorpoVazioQuandoCobrancaInexistente() throws Exception {
    when(consultarCobranca.executar("inexistente")).thenThrow(new CobrancaNaoEncontradaException());

    mockMvc
        .perform(get("/cobrancas/{assinaturaId}", "inexistente"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }
}
