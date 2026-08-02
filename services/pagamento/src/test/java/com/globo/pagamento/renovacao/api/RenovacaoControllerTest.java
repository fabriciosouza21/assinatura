package com.globo.pagamento.renovacao.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.ConsultarRenovacao;
import com.globo.pagamento.renovacao.RenovacaoNaoEncontradaException;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.shared.seguranca.SecurityConfig;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste de fatia web do {@link RenovacaoController}.
 *
 * <p>Verifica o mapeamento HTTP da consulta de renovacao: {@code 200} com o corpo contendo o {@code
 * paymentId} e o {@code statusTentativa} quando existe renovacao para a assinatura, sem exigir
 * autenticacao, espelhando o {@code /cobrancas}.
 */
@WebMvcTest(controllers = RenovacaoController.class)
@Import(SecurityConfig.class)
class RenovacaoControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ConsultarRenovacao consultarRenovacao;

  @Test
  @DisplayName("Deve retornar 200 com paymentId e statusTentativa quando a renovacao existe")
  void deveRetornar200ComDadosQuandoRenovacaoExiste() throws Exception {
    when(consultarRenovacao.executar("assinatura-uuid"))
        .thenReturn(
            new RenovacaoResponse(
                "assinatura-uuid",
                "renovacao-uuid",
                2,
                Plano.PREMIUM,
                new BigDecimal("39.90"),
                1,
                StatusTentativa.PENDENTE,
                "pay-456",
                Instant.parse("2026-08-02T12:00:00Z")));

    mockMvc
        .perform(get("/renovacoes/{assinaturaId}", "assinatura-uuid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.renovacaoId").value("renovacao-uuid"))
        .andExpect(jsonPath("$.paymentId").value("pay-456"))
        .andExpect(jsonPath("$.statusTentativa").value("PENDENTE"));
  }

  @Test
  @DisplayName("Deve retornar 404 com corpo vazio quando a renovacao nao existe")
  void deveRetornar404ComCorpoVazioQuandoRenovacaoInexistente() throws Exception {
    when(consultarRenovacao.executar("inexistente"))
        .thenThrow(new RenovacaoNaoEncontradaException());

    mockMvc
        .perform(get("/renovacoes/{assinaturaId}", "inexistente"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }
}
