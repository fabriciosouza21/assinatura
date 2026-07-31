package com.globo.pagamento.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.globo.pagamento.webhook.HmacSignatureValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

/**
 * Teste unitario do {@link GatewayWebClientConfig} quanto a validacao fail-fast da chave HMAC do
 * webhook.
 *
 * <p>Garante que o secret default {@code mock-webhook-secret} seja rejeitado fora do perfil {@code
 * dev}, impedindo bypass de autenticacao em ambientes nao-dev, e aceito quando o perfil {@code dev}
 * esta ativo.
 */
@ExtendWith(MockitoExtension.class)
class GatewayWebClientConfigTest {

  @Mock private Environment environment;

  @Test
  @DisplayName("Deve rejeitar o secret mock do webhook fora do perfil dev")
  void deveRejeitarSecretMockForaDoPerfilDev() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

    assertThatThrownBy(
            () ->
                new GatewayWebClientConfig()
                    .hmacSignatureValidator("mock-webhook-secret", environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("webhook-secret");
  }

  @Test
  @DisplayName("Deve aceitar o secret mock do webhook no perfil dev")
  void deveAceitarSecretMockNoPerfilDev() {
    when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

    HmacSignatureValidator validator =
        new GatewayWebClientConfig().hmacSignatureValidator("mock-webhook-secret", environment);

    assertThat(validator).as("Validador criado com o secret default no perfil dev").isNotNull();
  }
}
