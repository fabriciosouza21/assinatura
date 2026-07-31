package com.globo.pagamento.gateway;

import java.math.BigDecimal;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client do gateway de pagamento. Encapsula a chamada {@code POST /v1/payments} com a chave de
 * idempotencia e o valor em reais, e a consulta {@code GET /v1/payments/{id}} do status oficial.
 */
public class GatewayPagamentoClient {

  private final WebClient webClient;
  private final String notificationUrl;

  /**
   * Cria o client.
   *
   * @param webClient web client configurado com a base url do gateway
   * @param notificationUrl url para notificacoes de webhook futuras
   */
  public GatewayPagamentoClient(WebClient webClient, String notificationUrl) {
    this.webClient = webClient;
    this.notificationUrl = notificationUrl;
  }

  /**
   * Cria uma cobranca no gateway para a assinatura informada.
   *
   * @param assinaturaId identificador da assinatura, usado como chave de idempotencia e referencia
   *     externa
   * @param valor valor em reais, enviado sem conversao para centavos
   * @return resultado com o {@code paymentId} da cobranca criada
   */
  public CobrancaCriada criarCobranca(String assinaturaId, BigDecimal valor) {
    CreatePaymentRequest request =
        new CreatePaymentRequest(assinaturaId, valor, "BRL", "PIX", notificationUrl);
    CreatePaymentResponse response =
        webClient
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", assinaturaId)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(CreatePaymentResponse.class)
            .block();
    return new CobrancaCriada(response.id());
  }

  /**
   * Consulta o status oficial da cobranca no gateway.
   *
   * @param paymentId identificador da cobranca no gateway
   * @return status oficial reportado pelo gateway
   */
  public StatusGateway consultarStatus(String paymentId) {
    PaymentResponse response =
        webClient
            .get()
            .uri("/v1/payments/{id}", paymentId)
            .retrieve()
            .bodyToMono(PaymentResponse.class)
            .block();
    return StatusGateway.valueOf(response.status());
  }
}
