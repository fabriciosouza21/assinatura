package com.globo.pagamento.gateway;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

/**
 * Client do gateway de pagamento. Encapsula a chamada {@code POST /v1/payments} com a chave de
 * idempotencia e o valor em reais, e a consulta {@code GET /v1/payments/{id}} do status oficial.
 */
public class GatewayPagamentoClient {

  private static final Logger log = LoggerFactory.getLogger(GatewayPagamentoClient.class);

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
    ResponseEntity<CreatePaymentResponse> response =
        webClient
            .post()
            .uri("/v1/payments")
            .header("Idempotency-Key", assinaturaId)
            .bodyValue(request)
            .retrieve()
            .toEntity(CreatePaymentResponse.class)
            .block();
    log.info(
        "Cobranca criada no gateway para assinaturaId={} com httpStatus={}",
        assinaturaId,
        response.getStatusCode().value());
    return new CobrancaCriada(response.getBody().id());
  }

  /**
   * Cria uma cobranca no gateway para uma tentativa de renovacao da assinatura.
   *
   * <p>Falhas tecnicas de WebClient sao envolvidas em {@link CobrancaGatewayIndisponivelException};
   * demais excecoes propagam, distinguindo falha tecnica de bug.
   *
   * @param renovacaoId identificador da renovacao, usado como referencia externa
   * @param numero numero da tentativa de cobranca, combinado com {@code renovacaoId} para formar a
   *     chave de idempotencia
   * @param valor valor em reais, enviado sem conversao para centavos
   * @return resultado com o {@code paymentId} da cobranca criada
   */
  public CobrancaCriada criarCobrancaRenovacao(String renovacaoId, int numero, BigDecimal valor) {
    CreatePaymentRequest request =
        new CreatePaymentRequest(renovacaoId, valor, "BRL", "PIX", notificationUrl);
    try {
      CreatePaymentResponse response =
          webClient
              .post()
              .uri("/v1/payments")
              .header("Idempotency-Key", renovacaoId + ":" + numero)
              .bodyValue(request)
              .retrieve()
              .bodyToMono(CreatePaymentResponse.class)
              .block();
      return new CobrancaCriada(response.id());
    } catch (WebClientException e) {
      throw new CobrancaGatewayIndisponivelException(e);
    }
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
