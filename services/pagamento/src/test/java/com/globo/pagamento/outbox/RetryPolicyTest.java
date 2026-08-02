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
}
