package com.globo.pagamento.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Client do gateway de pagamento. Encapsula a chamada {@code POST /v1/payments} com a chave de
 * idempotencia e o valor em reais, e a consulta {@code GET /v1/payments/{id}} do status oficial.
 */
public class GatewayPagamentoClient {

  private static final Logger log = LoggerFactory.getLogger(GatewayPagamentoClient.class);

  private final WebClient webClient;
  private final String notificationUrl;
  private final Retry retry;
  private final MeterRegistry meterRegistry;

  /**
   * Cria o client sem politica de retry, executando uma unica tentativa por chamada.
   *
   * @param webClient web client configurado com a base url do gateway
   * @param notificationUrl url para notificacoes de webhook futuras
   */
  public GatewayPagamentoClient(WebClient webClient, String notificationUrl) {
    this(webClient, notificationUrl, Retry.max(0).filter(ignored -> false));
  }

  /**
   * Cria o client com politica de retry para a criacao de cobrancas de renovacao.
   *
   * @param webClient web client configurado com a base url do gateway
   * @param notificationUrl url para notificacoes de webhook futuras
   * @param retry politica de retry do Reactor aplicada a todas as chamadas ao gateway
   */
  public GatewayPagamentoClient(WebClient webClient, String notificationUrl, Retry retry) {
    this(webClient, notificationUrl, retry, null);
  }

  /**
   * Cria o client com politica de retry e instrumentacao de metricas para cada tentativa de retry.
   *
   * @param webClient web client configurado com a base url do gateway
   * @param notificationUrl url para notificacoes de webhook futuras
   * @param retry politica de retry do Reactor aplicada a todas as chamadas ao gateway
   * @param meterRegistry registro de metricas do Micrometer; pode ser {@code null} para desativar a
   *     instrumentacao de retry
   */
  public GatewayPagamentoClient(
      WebClient webClient, String notificationUrl, Retry retry, MeterRegistry meterRegistry) {
    this.webClient = webClient;
    this.notificationUrl = notificationUrl;
    this.retry = retry;
    this.meterRegistry = meterRegistry;
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
        bloquearComRetry(
            webClient
                .post()
                .uri("/v1/payments")
                .header("Idempotency-Key", assinaturaId)
                .bodyValue(request)
                .retrieve()
                .toEntity(CreatePaymentResponse.class),
            "criarCobranca");
    log.atInfo()
        .addKeyValue("event", "cobranca_criada_gateway")
        .addKeyValue("assinaturaId", assinaturaId)
        .addKeyValue("httpStatus", response.getStatusCode().value())
        .log("Cobranca criada no gateway");
    return new CobrancaCriada(response.getBody().id());
  }

  /**
   * Cria uma cobranca no gateway para uma tentativa de renovacao da assinatura.
   *
   * <p>Falhas tecnicas de WebClient e a exaustao do retry sao envolvidas em {@link
   * CobrancaGatewayIndisponivelException}; demais excecoes propagam, distinguindo falha tecnica de
   * bug.
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
    CreatePaymentResponse response =
        bloquearComRetry(
            webClient
                .post()
                .uri("/v1/payments")
                .header("Idempotency-Key", renovacaoId + ":" + numero)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(CreatePaymentResponse.class),
            "criarCobrancaRenovacao");
    return new CobrancaCriada(response.id());
  }

  /**
   * Consulta o status oficial da cobranca no gateway.
   *
   * <p>Falhas tecnicas de WebClient e a exaustao do retry sao envolvidas em {@link
   * CobrancaGatewayIndisponivelException}; demais excecoes propagam, distinguindo falha tecnica de
   * bug.
   *
   * @param paymentId identificador da cobranca no gateway
   * @return status oficial reportado pelo gateway
   */
  public StatusGateway consultarStatus(String paymentId) {
    PaymentResponse response =
        bloquearComRetry(
            webClient
                .get()
                .uri("/v1/payments/{id}", paymentId)
                .retrieve()
                .bodyToMono(PaymentResponse.class),
            "consultarStatus");
    return StatusGateway.valueOf(response.status());
  }

  private <T> T bloquearComRetry(Mono<T> chamada, String metodo) {
    try {
      Retry retryEfetivo =
          meterRegistry == null
              ? retry
              : Retry.from(
                  companion ->
                      Flux.from(retry.generateCompanion(companion))
                          .doOnNext(sinal -> incrementarRetry(metodo)));
      return chamada.retryWhen(retryEfetivo).block();
    } catch (WebClientException e) {
      throw new CobrancaGatewayIndisponivelException(e);
    }
  }

  private void incrementarRetry(String metodo) {
    meterRegistry.counter("pagamento.gateway.retry.tentativas", "metodo", metodo).increment();
  }
}
