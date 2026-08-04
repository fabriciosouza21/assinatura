package com.globo.pagamento.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Teste unitario do {@link KafkaConsumerConfig}.
 *
 * <p>Garante que o backoff do error handler aplica jitter (exponencial), atendendo ao requisito do
 * contrato {@code docs/contratos/contrato-eventos-kafka.puml} de "3 tentativas + backoff + jitter".
 */
class KafkaConsumerConfigTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);

  private final KafkaConsumerConfig config = new KafkaConsumerConfig();

  @Test
  @DisplayName("Backoff do error handler deve aplicar jitter entre tentativas")
  void backoffDeveAplicarJitter() {
    BackOff backOff = config.backoff();

    assertThat(backOff).as("Backoff nao deve ser fixo").isNotInstanceOf(FixedBackOff.class);
    assertThat(backOff)
        .as("Backoff deve ser exponencial com jitter")
        .isInstanceOf(ExponentialBackOff.class);
    ExponentialBackOff exponencial = (ExponentialBackOff) backOff;
    assertThat(exponencial.getJitter()).as("Jitter positivo para variar o intervalo").isPositive();
    assertThat(exponencial.getMaxAttempts()).as("Ate 3 tentativas antes da DLQ").isEqualTo(3L);
  }
}
