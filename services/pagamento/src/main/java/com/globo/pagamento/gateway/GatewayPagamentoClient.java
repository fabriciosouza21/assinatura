package com.globo.pagamento.gateway;

import java.math.BigDecimal;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client do gateway de pagamento. Encapsula a chamada {@code POST /v1/payments} com a chave de
 * idempotencia e o valor em reais.
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
   * @return resultado com o {@code paymentId} e o {@code status} inicial
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
    return new CobrancaCriada(response.id(), response.status());
  }

  private record CreatePaymentRequest(
      String externalReference,
      BigDecimal amount,
      String currency,
      String paymentMethod,
      String notificationUrl) {}

  private record CreatePaymentResponse(
      String id,
      String externalReference,
      BigDecimal amount,
      String currency,
      String paymentMethod,
      String status) {}
}
