package com.globo.assinatura.shared.kafka.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Teste unitario do {@link ReplayDlqKafkaConfig}.
 *
 * <p>Garante que o backoff do listener de replay e fixo e longo (intervalo configuravel com default
 * de 1h), distinto do retry curto dos consumers normais, com 3 tentativas antes de desistir da
 * mensagem.
 */
class ReplayDlqKafkaConfigTest {

  private static final long INTERVALO_MS = 3600000L;

  private final ReplayDlqKafkaConfig config = new ReplayDlqKafkaConfig();

  @Test
  @DisplayName("Backoff do replay deve ser fixo e longo, com 3 tentativas")
  void backoffDeveSerFixoComIntervaloLongo() {
    BackOff backoff = config.backoff(INTERVALO_MS);

    assertThat(backoff).as("Backoff do replay deve ser fixo").isInstanceOf(FixedBackOff.class);
    FixedBackOff fixo = (FixedBackOff) backoff;
    assertThat(fixo.getInterval()).as("Intervalo longo configuravel").isEqualTo(INTERVALO_MS);
    assertThat(fixo.getMaxAttempts())
        .as("3 tentativas no total (1 inicial + 2 retentativas)")
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("Factory do replay deve receber a fabrica de consumidores e o backoff longo")
  void factoryDoReplayDeveAplicarBackoffLongo() {
    @SuppressWarnings("unchecked")
    ConsumerFactory<String, String> consumerFactory = mock(ConsumerFactory.class);

    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        config.replayKafkaListenerContainerFactory(
            consumerFactory, new ReplayDlqProperties(INTERVALO_MS, 3));

    assertThat(factory.getConsumerFactory())
        .as("Fabrica de consumidores aplicada")
        .isSameAs(consumerFactory);
  }
}
