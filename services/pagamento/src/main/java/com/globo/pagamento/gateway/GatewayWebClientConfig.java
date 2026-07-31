package com.globo.pagamento.gateway;

import com.globo.pagamento.webhook.HmacSignatureValidator;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Configuracao do {@link WebClient} do gateway de pagamento, do {@link GatewayPagamentoClient} e do
 * {@link HmacSignatureValidator} que valida o webhook.
 */
@Configuration
public class GatewayWebClientConfig {

  /**
   * Cria o client do gateway com a base url e os timeouts de conexao e leitura.
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
                    HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                        .responseTimeout(Duration.ofSeconds(10))))
            .build();
    return new GatewayPagamentoClient(webClient, webhookUrl);
  }

  /**
   * Cria o validador de assinatura HMAC do webhook com a chave compartilhada com o gateway.
   *
   * <p>Rejeita o secret default {@code mock-webhook-secret} (ou em branco) fora do perfil {@code
   * dev}, impedindo que o servico suba em ambientes nao-dev com a autenticacao do webhook exposta
   * pela chave publica do repositorio.
   *
   * @param webhookSecret chave HMAC, via {@code app.gateway.webhook-secret}
   * @param environment ambiente do Spring, para inspecionar o perfil ativo
   * @return o {@link HmacSignatureValidator} configurado
   * @throws IllegalStateException se o secret for o default ou em branco fora do perfil {@code dev}
   */
  @Bean
  public HmacSignatureValidator hmacSignatureValidator(
      @Value("${app.gateway.webhook-secret}") String webhookSecret, Environment environment) {
    if (!isDev(environment)
        && (webhookSecret == null
            || webhookSecret.isBlank()
            || "mock-webhook-secret".equals(webhookSecret))) {
      throw new IllegalStateException(
          "app.gateway.webhook-secret deve ser definida fora do perfil dev");
    }
    return new HmacSignatureValidator(webhookSecret);
  }

  private static boolean isDev(Environment environment) {
    return Arrays.asList(environment.getActiveProfiles()).contains("dev");
  }
}
