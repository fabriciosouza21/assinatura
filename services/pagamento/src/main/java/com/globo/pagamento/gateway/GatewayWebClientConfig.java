package com.globo.pagamento.gateway;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Configuracao do {@link WebClient} do gateway de pagamento e do {@link GatewayPagamentoClient}.
 */
@Configuration
public class GatewayWebClientConfig {

  /**
   * Cria o client do gateway com a base url e timeout de leitura.
   *
   * @param baseUrl url base do gateway, via {@code app.gateway.base-url}
   * @param webhookUrl url de notificacoes de webhook, via {@code
   *     app.gateway.webhook-notification-url}
   * @return o {@link GatewayPagamentoClient} configurado
   */
  @Bean
  public GatewayPagamentoClient gatewayPagamentoClient(
      @Value("${app.gateway.base-url}") String baseUrl,
      @Value("${app.gateway.webhook-notification-url}") String webhookUrl) {
    WebClient webClient =
        WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(10))))
            .build();
    return new GatewayPagamentoClient(webClient, webhookUrl);
  }
}
