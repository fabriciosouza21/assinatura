package com.globo.pagamento.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.webhook.api.HmacSignatureValidator;
import com.globo.pagamento.webhook.api.WebhookConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Teste unitario do {@link GatewayWebClientConfig}.
 *
 * <p>Garante que o secret default {@code mock-webhook-secret} seja rejeitado fora do perfil {@code
 * dev}, impedindo bypass de autenticacao em ambientes nao-dev, e aceito quando o perfil {@code dev}
 * esta ativo. Verifica ainda que o {@link WebClient} do gateway e construido a partir do {@link
 * WebClient.Builder} injetado pelo Spring, carregando a instrumentacao de tracing distribuido.
 */
@ExtendWith(MockitoExtension.class)
class GatewayWebClientConfigTest {

  @Mock private Environment environment;

  @Test
  @DisplayName("Deve rejeitar o secret mock do webhook fora do perfil dev")
  void deveRejeitarSecretMockForaDoPerfilDev() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    assertThatThrownBy(
            () -> new WebhookConfig().hmacSignatureValidator("mock-webhook-secret", environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("webhook-secret");
  }

  @Test
  @DisplayName("Deve aceitar o secret mock do webhook no perfil dev")
  void deveAceitarSecretMockNoPerfilDev() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

    HmacSignatureValidator validator =
        new WebhookConfig().hmacSignatureValidator("mock-webhook-secret", environment);

    assertThat(validator).as("Validador criado com o secret default no perfil dev").isNotNull();
  }

  @Test
  @DisplayName("Deve construir o WebClient do gateway a partir do Builder injetado pelo Spring")
  void deveConstruirWebClientDoBuilderInjetado() {
    WebClient.Builder builderSpy = Mockito.spy(WebClient.builder().baseUrl("http://gw"));

    new GatewayWebClientConfig().gatewayPagamentoClient("http://gw", "http://hook", builderSpy);

    verify(builderSpy).build();
  }

  @Test
  @DisplayName("Deve aplicar o conector HTTP ao Builder injetado pelo Spring")
  void deveAplicarConectorHttpAoBuilderInjetado() {
    WebClient.Builder builderSpy = Mockito.spy(WebClient.builder().baseUrl("http://gw"));

    new GatewayWebClientConfig().gatewayPagamentoClient("http://gw", "http://hook", builderSpy);

    verify(builderSpy).clientConnector(any(ClientHttpConnector.class));
  }
}
