package com.globo.pagamento.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Configuracao do {@link WebClient} do gateway de pagamento e do {@link GatewayPagamentoClient}.
 */
@Configuration
@EnableConfigurationProperties(GatewayRetryProperties.class)
public class GatewayWebClientConfig {

  /**
   * Cria o client do gateway com a base url, os timeouts de conexao e leitura e a politica de retry
   * com backoff configurados.
   *
   * @param baseUrl url base do gateway, via {@code app.gateway.base-url}
   * @param webhookUrl url de notificacoes de webhook, via {@code
   *     app.gateway.webhook-notification-url}
   * @param builder {@link WebClient.Builder} injetado pelo Spring, que carrega a instrumentacao de
   *     tracing (ex.: OpenTelemetry) registrada nos autoconfiguradores do Spring Boot; eh o ponto
   *     de entrada a ser usado para construir o {@link WebClient} de forma observavel
   * @param timeoutConexaoMs timeout de conexao em milissegundos, via {@code
   *     app.gateway.timeout-conexao-ms}
   * @param timeoutRespostaMs timeout de resposta em milissegundos, via {@code
   *     app.gateway.timeout-resposta-ms}
   * @param retryProperties parametros do retry com backoff, via {@code app.gateway.retry.*}
   * @param meterRegistry registro de metricas do Micrometer para instrumentar as tentativas de
   *     retry; pode ser {@code null}
   * @return o {@link GatewayPagamentoClient} configurado
   */
  @Bean
  public GatewayPagamentoClient gatewayPagamentoClient(
      @Value("${app.gateway.base-url}") String baseUrl,
      @Value("${app.gateway.webhook-notification-url}") String webhookUrl,
      WebClient.Builder builder,
      @Value("${app.gateway.timeout-conexao-ms:2000}") int timeoutConexaoMs,
      @Value("${app.gateway.timeout-resposta-ms:10000}") int timeoutRespostaMs,
      GatewayRetryProperties retryProperties,
      MeterRegistry meterRegistry) {
    WebClient webClient =
        builder
            .baseUrl(baseUrl)
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutConexaoMs)
                        .responseTimeout(Duration.ofMillis(timeoutRespostaMs))))
            .build();
    return new GatewayPagamentoClient(
        webClient,
        webhookUrl,
        GatewayRetryPolicy.criar(
            retryProperties.maxAttempts(),
            Duration.ofSeconds(retryProperties.backoffInicialSegundos()),
            retryProperties.multiplicador(),
            retryProperties.jitter()),
        meterRegistry);
  }
}
