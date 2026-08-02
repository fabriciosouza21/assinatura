package com.globo.pagamento.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  @DisplayName("Deve indicar sem tentativas restantes no limite maximo")
  void deveIndicarSemTentativasNoLimiteMaximo() {
    RetryPolicy politica = new RetryPolicy(3, Duration.ofSeconds(30), Duration.ofMillis(100));

    assertThat(politica.temTentativasRestantes(3))
        .as("Tentativas restantes no limite maximo")
        .isFalse();
  }

  @Test
  @DisplayName("Deve calcular atraso com backoff exponencial e jitter")
  void deveCalcularAtrasoComBackoffExponencial() {
    RetryPolicy politica = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMillis(500));
    Duration atraso = politica.calcularProximoAtraso(2);

    assertThat(atraso)
        .as("Atraso com backoff exponencial e jitter")
        .isBetween(Duration.ofSeconds(2), Duration.ofSeconds(2).plusMillis(500));
  }

  @Test
  @DisplayName("Deve indicar tentativas restantes abaixo do limite maximo")
  void deveIndicarTentativasRestantesAbaixoDoLimiteMaximo() {
    RetryPolicy politica = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMillis(500));

    assertThat(politica.temTentativasRestantes(2))
        .as("Tentativas restantes abaixo do limite maximo")
        .isTrue();
  }
}
