package com.globo.pagamento.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste do calculo de backoff exponencial do {@link GatewayRetryPolicy}.
 *
 * <p>Verifica a formula {@code base = backoffInicial * multiplicador^(tentativa-1)} com o jitter
 * aplicado como fator fracionario ao redor da base, usando {@code Random} com seed fixo para manter
 * o determinismo.
 */
class GatewayRetryPolicyTest {

  @Test
  @DisplayName("Deve calcular backoff exponencial com multiplicador e jitter")
  void deveCalcularBackoffExponencialComJitterDeterministico() {
    Random aleatorio = new Random(42L);
    Duration backoff1 =
        GatewayRetryPolicy.calcularBackoff(1, Duration.ofSeconds(1), 2.0, 0.1, aleatorio);
    Duration backoff2 =
        GatewayRetryPolicy.calcularBackoff(2, Duration.ofSeconds(1), 2.0, 0.1, aleatorio);
    Duration backoff3 =
        GatewayRetryPolicy.calcularBackoff(3, Duration.ofSeconds(1), 2.0, 0.1, aleatorio);

    assertThat(backoff1)
        .as("Backoff da tentativa 1 em torno da base de 1000ms")
        .isBetween(Duration.ofMillis(900), Duration.ofMillis(1100));
    assertThat(backoff2)
        .as("Backoff da tentativa 2 em torno da base de 2000ms")
        .isBetween(Duration.ofMillis(1800), Duration.ofMillis(2200));
    assertThat(backoff3)
        .as("Backoff da tentativa 3 em torno da base de 4000ms")
        .isBetween(Duration.ofMillis(3600), Duration.ofMillis(4400));
    assertThat(backoff2).as("Backoff crescente entre tentativa 1 e 2").isGreaterThan(backoff1);
    assertThat(backoff3).as("Backoff crescente entre tentativa 2 e 3").isGreaterThan(backoff2);
  }

  @Test
  @DisplayName("Deve calcular backoff exato quando o jitter e zero")
  void deveCalcularBackoffExatoSemJitter() {
    Random aleatorio = new Random(7L);
    Duration backoff1 =
        GatewayRetryPolicy.calcularBackoff(1, Duration.ofSeconds(1), 3.0, 0.0, aleatorio);
    Duration backoff2 =
        GatewayRetryPolicy.calcularBackoff(2, Duration.ofSeconds(1), 3.0, 0.0, aleatorio);
    Duration backoff3 =
        GatewayRetryPolicy.calcularBackoff(3, Duration.ofSeconds(1), 3.0, 0.0, aleatorio);

    assertThat(backoff1)
        .as("Backoff da tentativa 1 com multiplicador 3 e jitter zero")
        .isEqualByComparingTo(Duration.ofSeconds(1));
    assertThat(backoff2)
        .as("Backoff da tentativa 2 com multiplicador 3 e jitter zero")
        .isEqualByComparingTo(Duration.ofSeconds(3));
    assertThat(backoff3)
        .as("Backoff da tentativa 3 com multiplicador 3 e jitter zero")
        .isEqualByComparingTo(Duration.ofSeconds(9));
  }

  @Test
  @DisplayName("Deve rejeitar multiplicador menor que um na criacao da politica")
  void deveRejeitarMultiplicadorInvalidoNaCriacao() {
    assertThatThrownBy(() -> GatewayRetryPolicy.criar(3, Duration.ofSeconds(1), 0.5, 0.1))
        .as("Criacao da politica com multiplicador menor que 1")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Multiplicador");
  }

  @Test
  @DisplayName("Deve rejeitar jitter fora de zero a um na criacao da politica")
  void deveRejeitarJitterForaDaFaixaNaCriacao() {
    assertThatThrownBy(() -> GatewayRetryPolicy.criar(3, Duration.ofSeconds(1), 2.0, 1.5))
        .as("Criacao da politica com jitter fora de [0, 1]")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Jitter");
  }
}
