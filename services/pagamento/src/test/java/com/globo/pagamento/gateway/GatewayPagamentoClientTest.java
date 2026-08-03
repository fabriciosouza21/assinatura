package com.globo.pagamento.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste do {@link GatewayPagamentoClient} contra o gateway mock via MockWebServer.
 *
 * <p>Verifica o contrato {@code POST /v1/payments}: o header {@code Idempotency-Key} com o {@code
 * assinaturaId}, o body com {@code amount} em reais (sem conversao para centavos) e o mapeamento da
 * resposta para {@code paymentId}.
 */
class GatewayPagamentoClientTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
  private MockWebServer server;
  private GatewayPagamentoClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    client = new GatewayPagamentoClient(webClient, "http://pagamento:8080/webhooks/payments");
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("Deve criar cobranca com Idempotency-Key e amount em reais")
  void deveCriarCobranca() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                jsonMapper.writeValueAsString(
                    new CreatePaymentResponse(
                        "pay_123", "assinatura-uuid", new BigDecimal("19.90"), "BRL", "PIX")))
            .setResponseCode(201));

    CobrancaCriada cobranca = client.criarCobranca("assinatura-uuid", new BigDecimal("19.90"));

    assertThat(cobranca.paymentId()).as("PaymentId do gateway").isEqualTo("pay_123");

    RecordedRequest requisicao = server.takeRequest();
    assertThat(requisicao.getPath()).as("Path da criacao de cobranca").isEqualTo("/v1/payments");
    assertThat(requisicao.getHeader("Idempotency-Key"))
        .as("Header de idempotencia com o assinaturaId")
        .isEqualTo("assinatura-uuid");

    JsonNode body = jsonMapper.readTree(requisicao.getBody().readUtf8());
    assertThat(body.get("amount").decimalValue())
        .as("Amount em reais, sem conversao para centavos")
        .isEqualByComparingTo("19.90");
    assertThat(body.get("currency").asText()).as("Moeda").isEqualTo("BRL");
    assertThat(body.get("paymentMethod").asText()).as("Meio de pagamento").isEqualTo("PIX");
    assertThat(body.get("externalReference").asText())
        .as("Referencia externa = assinaturaId")
        .isEqualTo("assinatura-uuid");
  }

  @Test
  @DisplayName("Deve criar cobranca de renovacao com Idempotency-Key=renovacaoId:numero")
  void deveEnviarChaveIdempotenciaCompostaNaCobrancaDeRenovacao() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                jsonMapper.writeValueAsString(
                    new CreatePaymentResponse(
                        "pay_renov", "renov-123", new BigDecimal("19.90"), "BRL", "PIX")))
            .setResponseCode(201));

    client.criarCobrancaRenovacao("renov-123", 1, new BigDecimal("19.90"));

    RecordedRequest requisicao = server.takeRequest();
    assertThat(requisicao.getHeader("Idempotency-Key"))
        .as("Header de idempotencia composto por renovacaoId:numeroTentativa")
        .isEqualTo("renov-123:1");
  }

  @Test
  @DisplayName("Deve retornar o payment id da cobranca de renovacao criada no gateway")
  void deveRetornarPaymentIdDaCobrancaDeRenovacao() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                jsonMapper.writeValueAsString(
                    new CreatePaymentResponse(
                        "pay_renov", "renov-123", new BigDecimal("19.90"), "BRL", "PIX")))
            .setResponseCode(201));

    CobrancaCriada resultado =
        client.criarCobrancaRenovacao("renov-123", 1, new BigDecimal("19.90"));

    assertThat(resultado.paymentId())
        .as("payment id retornado pelo gateway")
        .isEqualTo("pay_renov");
  }

  @Test
  @DisplayName("Deve envolver falha tecnica do gateway em CobrancaGatewayIndisponivelException")
  void deveEnvolverFalhaTecnicaDoGatewayEmExcecaoDeDominio() {
    server.enqueue(new MockResponse().setResponseCode(500));

    assertThatThrownBy(() -> client.criarCobrancaRenovacao("renov-123", 1, new BigDecimal("19.90")))
        .as("falha tecnica do gateway deve virar excecao de dominio")
        .isInstanceOf(CobrancaGatewayIndisponivelException.class);
  }

  @Test
  @DisplayName("Deve retentar com backoff quando o gateway responde 500 e depois 201")
  void deveRetentarComBackoffAposErroTransitorioDoGateway() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                jsonMapper.writeValueAsString(
                    new CreatePaymentResponse(
                        "pay_renov", "renov-123", new BigDecimal("19.90"), "BRL", "PIX")))
            .setResponseCode(201));
    GatewayPagamentoClient clientComRetry =
        new GatewayPagamentoClient(
            WebClient.builder().baseUrl(server.url("/").toString()).build(),
            "http://pagamento:8080/webhooks/payments",
            Retry.backoff(3, Duration.ofMillis(5)));

    CobrancaCriada cobranca =
        clientComRetry.criarCobrancaRenovacao("renov-123", 1, new BigDecimal("19.90"));

    assertThat(cobranca.paymentId())
        .as("payment id retornado apos retry com sucesso")
        .isEqualTo("pay_renov");
    assertThat(server.getRequestCount()).as("quantidade de tentativas no gateway").isEqualTo(2);
    RecordedRequest primeiraTentativa = server.takeRequest();
    RecordedRequest segundaTentativa = server.takeRequest();
    assertThat(primeiraTentativa.getHeader("Idempotency-Key"))
        .as("Idempotency-Key da primeira tentativa")
        .isEqualTo("renov-123:1");
    assertThat(segundaTentativa.getHeader("Idempotency-Key"))
        .as("Idempotency-Key da segunda tentativa")
        .isEqualTo("renov-123:1");
  }

  @Test
  @DisplayName("Deve consultar o status oficial da cobranca por paymentId")
  void deveConsultarStatusPorPaymentId() throws Exception {
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                jsonMapper.writeValueAsString(
                    new PaymentResponse(
                        "pay_123",
                        "assinatura-uuid",
                        new BigDecimal("19.90"),
                        "BRL",
                        "PIX",
                        "APPROVED")))
            .setResponseCode(200));

    StatusGateway status = client.consultarStatus("pay_123");

    assertThat(status)
        .as("Status oficial retornado pelo gateway")
        .isEqualTo(StatusGateway.APPROVED);

    RecordedRequest requisicao = server.takeRequest();
    assertThat(requisicao.getMethod()).as("Metodo da consulta").isEqualTo("GET");
    assertThat(requisicao.getPath())
        .as("Path da consulta de status")
        .isEqualTo("/v1/payments/pay_123");
  }
}
