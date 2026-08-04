package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  @DisplayName("Deve calcular atraso de backoff exponencial dentro do range com jitter")
  void deveCalcularAtrasoDeBackoffExponencialDentroDoRange() {
    Duration base = Duration.ofSeconds(1);
    Duration jitter = Duration.ofMillis(500);
    RetryPolicy policy = new RetryPolicy(3, base, jitter);

    Duration atraso = policy.calcularProximoAtraso(1);

    assertThat(atraso)
        .as("Atraso da 1a tentativa entre base e base+jitter")
        .isBetween(base, base.plus(jitter));
  }

  @Test
  @DisplayName("Deve indicar tentativas esgotadas ao atingir o maximo")
  void deveIndicarTentativasEsgotadasAoAtingirMaximo() {
    RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMillis(500));

    assertThat(policy.temTentativasRestantes(2)).as("Ainda ha tentativa antes do maximo").isTrue();
    assertThat(policy.temTentativasRestantes(3))
        .as("Maximo atingido esgota as tentativas")
        .isFalse();
  }
}
