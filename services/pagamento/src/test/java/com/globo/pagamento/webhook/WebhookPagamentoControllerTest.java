package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.globo.pagamento.security.SecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de fatia web do {@link WebhookPagamentoController}.
 *
 * <p>Verifica o mapeamento HTTP do webhook: {@code 200} para notificacao valida, {@code 401} para
 * assinatura HMAC invalida e {@code 503} para falha de publicacao, com o corpo de erro padronizado.
 */
@WebMvcTest(controllers = WebhookPagamentoController.class)
@Import(SecurityConfig.class)
class WebhookPagamentoControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ProcessarWebhookPagamento command;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve retornar 200 com o eventId quando a notificacao e valida")
  void deveRetornar200QuandoNotificacaoValida() throws Exception {
    when(command.processar(any(), any(), any(), any(), any())).thenReturn(EVENT_ID);

    mockMvc
        .perform(
            post("/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mock-Event-Id", EVENT_ID.toString())
                .header("X-Mock-Signature", "sha256=abc")
                .content(corpoValido()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(true))
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()));
  }

  @Test
  @DisplayName("Deve retornar 401 quando a assinatura HMAC e invalida")
  void deveRetornar401QuandoAssinaturaInvalida() throws Exception {
    when(command.processar(any(), any(), any(), any(), any()))
        .thenThrow(new WebhookInvalidoException());

    mockMvc
        .perform(
            post("/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mock-Event-Id", EVENT_ID.toString())
                .header("X-Mock-Signature", "sha256=invalida")
                .content(corpoValido()))
        .andExpect(status().isUnauthorized())
        .andExpect(content().json("{\"erro\":\"assinatura_hmac_invalida\"}"));
  }

  @Test
  @DisplayName("Deve logar ERROR quando o comando lanca excecao nao tratada")
  void deveLogarErroQuandoComandoLancaExcecaoNaoTratada() throws Exception {
    when(command.processar(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    Logger logger = (Logger) LoggerFactory.getLogger(WebhookExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      mockMvc
          .perform(
              post("/webhooks/payments")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("X-Mock-Event-Id", EVENT_ID.toString())
                  .header("X-Mock-Signature", "sha256=abc")
                  .content(corpoValido()))
          .andReturn();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count())
        .as("deve registrar ao menos um evento de ERROR no handler catch-all")
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  @DisplayName("Deve retornar 503 quando a publicacao falha")
  void deveRetornar503QuandoPublicacaoFalha() throws Exception {
    when(command.processar(any(), any(), any(), any(), any()))
        .thenThrow(new PublicacaoIndisponivelException());

    mockMvc
        .perform(
            post("/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mock-Event-Id", EVENT_ID.toString())
                .header("X-Mock-Signature", "sha256=abc")
                .content(corpoValido()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().json("{\"erro\":\"publicacao_indisponivel\"}"));
  }

  @Test
  @DisplayName("Deve logar ERROR quando a publicacao falha")
  void deveLogarErroQuandoPublicacaoFalha() throws Exception {
    when(command.processar(any(), any(), any(), any(), any()))
        .thenThrow(new PublicacaoIndisponivelException());

    Logger logger = (Logger) LoggerFactory.getLogger(WebhookExceptionHandler.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      mockMvc
          .perform(
              post("/webhooks/payments")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("X-Mock-Event-Id", EVENT_ID.toString())
                  .header("X-Mock-Signature", "sha256=abc")
                  .content(corpoValido()))
          .andExpect(status().isServiceUnavailable());
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count())
        .as("deve registrar ao menos um evento de ERROR na falha de publicacao")
        .isGreaterThanOrEqualTo(1L);
  }

  private String corpoValido() {
    return jsonMapper.writeValueAsString(
        new WebhookEvent(
            EVENT_ID,
            "payment.updated",
            new WebhookData(
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                UUID.fromString("00000000-0000-0000-0000-000000000011"))));
  }
}
