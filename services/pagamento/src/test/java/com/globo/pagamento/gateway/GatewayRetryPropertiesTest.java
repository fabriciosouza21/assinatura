package com.globo.pagamento.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

/**
 * Teste do record de configuracao {@link GatewayRetryProperties}.
 *
 * <p>Verifica a vinculacao dos parametros de retry em {@code app.gateway.retry} via {@link Binder}
 * e a validacao fail-fast do construtor compacto com valores invalidos.
 */
class GatewayRetryPropertiesTest {

  @Test
  @DisplayName("Deve vincular parametros de retry da configuracao app.gateway.retry")
  void deveVincularParametrosDeRetryDaConfiguracao() {
    MapPropertySource fonte =
        new MapPropertySource(
            "app.gateway.retry",
            Map.of(
                "app.gateway.retry.max-attempts", "5",
                "app.gateway.retry.backoff-inicial-segundos", "2",
                "app.gateway.retry.multiplicador", "3.0",
                "app.gateway.retry.jitter", "0.2"));
    Binder binder = new Binder(ConfigurationPropertySources.from(fonte));
    GatewayRetryProperties propriedades =
        binder.bind("app.gateway.retry", Bindable.of(GatewayRetryProperties.class)).get();

    assertThat(propriedades.maxAttempts()).as("max-attempts vinculado").isEqualTo(5);
    assertThat(propriedades.backoffInicialSegundos())
        .as("backoff-inicial-segundos vinculado")
        .isEqualTo(2);
    assertThat(propriedades.multiplicador()).as("multiplicador vinculado").isEqualTo(3.0);
    assertThat(propriedades.jitter()).as("jitter vinculado").isEqualTo(0.2);
  }

  @Test
  @DisplayName("Deve rejeitar parametros de retry invalidos na criacao")
  void deveRejeitarParametrosInvalidosDeRetry() {
    assertThatThrownBy(() -> new GatewayRetryProperties(0, 1, 2.0, 0.5))
        .as("Criacao com max-attempts zero")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, 1.0, 1.5))
        .as("Criacao com jitter acima de um")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve rejeitar backoff inicial abaixo de um segundo na criacao")
  void deveRejeitarBackoffInicialAbaixoDeUmSegundo() {
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 0, 2.0, 0.5))
        .as("Criacao com backoff inicial zero")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayRetryProperties(3, -1, 2.0, 0.5))
        .as("Criacao com backoff inicial negativo")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve rejeitar multiplicador nao finito (NaN ou infinito) na criacao")
  void deveRejeitarMultiplicadorNaoFinito() {
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, Double.NaN, 0.5))
        .as("Criacao com multiplicador NaN")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, Double.POSITIVE_INFINITY, 0.5))
        .as("Criacao com multiplicador infinito")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve rejeitar jitter nao finito (NaN ou infinito) na criacao")
  void deveRejeitarJitterNaoFinito() {
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, 2.0, Double.NaN))
        .as("Criacao com jitter NaN")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, 2.0, Double.POSITIVE_INFINITY))
        .as("Criacao com jitter infinito")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
