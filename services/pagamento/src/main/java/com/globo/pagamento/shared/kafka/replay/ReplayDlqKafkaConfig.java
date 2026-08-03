package com.globo.pagamento.shared.kafka.replay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configuracao da fabrica de containers do listener de replay da DLQ.
 *
 * <p>Cria uma fabrica propria para o {@link ReplayDlqConsumer}, com {@link DefaultErrorHandler} de
 * backoff fixo longo (default 1h, {@code app.kafka.replay.backoff-interval-ms}), distinto do retry
 * curto dos consumers normais. O listener de replay nao publica em DLQ: mensagens que seguem
 * falhando no envio sao retentadas no intervalo longo e, esgotadas as tentativas, registradas como
 * erro pelo handler.
 */
@Configuration
@EnableConfigurationProperties(ReplayDlqProperties.class)
public class ReplayDlqKafkaConfig {

  /**
   * Cria a fabrica de containers do listener de replay com o backoff longo.
   *
   * @param consumerFactory fabrica de consumidores injetada pelo Spring
   * @param properties configuracao do intervalo longo do replay
   * @return fabrica de containers configurada com o error handler de backoff longo
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String>
      replayKafkaListenerContainerFactory(
          ConsumerFactory<String, String> consumerFactory, ReplayDlqProperties properties) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(new DefaultErrorHandler(backoff(properties.backoffIntervalMs())));
    return factory;
  }

  /**
   * Constroi o backoff fixo longo para o listener de replay.
   *
   * @param intervaloMs intervalo entre tentativas, em milissegundos
   * @return backoff fixo com 3 tentativas no total
   */
  BackOff backoff(long intervaloMs) {
    return new FixedBackOff(intervaloMs, 2L);
  }
}
