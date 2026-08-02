package com.globo.pagamento.shared.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Configuracao do tratamento de erros do consumer Kafka do Pagamento Service.
 *
 * <p>Define o {@link DefaultErrorHandler} com ate 3 tentativas (backoff exponencial com jitter) e
 * {@link DeadLetterPublishingRecoverer} enviando para {@code <topico>-dlq}. Eventos invalidos
 * ({@link EventoInvalidoException}) sao tratados como nao retentaveis: vao direto para a DLQ, sem
 * retry e sem chamada ao gateway.
 */
@Configuration
public class KafkaConsumerConfig {

  /**
   * Cria o error handler do consumer, registrado automaticamente na fabrica de listeners pelo
   * Spring Kafka.
   *
   * @param template template para publicar na DLQ
   * @return error handler com retry limitado, DLQ e excecoes nao retentaveis
   */
  @Bean
  public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (consumerResource, ex) -> {
              String topicoDlq = consumerResource.topic() + "-dlq";
              return new TopicPartition(topicoDlq, consumerResource.partition());
            });
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff());
    handler.addNotRetryableExceptions(EventoInvalidoException.class);
    return handler;
  }

  /**
   * Constroi o backoff exponencial com jitter para as retentativas do consumer.
   *
   * <p>O jitter varia o intervalo entre tentativas para evitar thundering-herd contra o gateway
   * durante falhas concentradas, atendendo ao requisito do contrato de "3 tentativas + backoff +
   * jitter".
   *
   * @return backoff exponencial com no maximo 3 tentativas
   */
  BackOff backoff() {
    ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setJitter(500L);
    backOff.setMaxAttempts(3L);
    return backOff;
  }
}
