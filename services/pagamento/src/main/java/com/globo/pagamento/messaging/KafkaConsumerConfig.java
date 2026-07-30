package com.globo.pagamento.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configuracao do tratamento de erros do consumer Kafka do Pagamento Service.
 *
 * <p>Define o {@link DefaultErrorHandler} com ate 3 tentativas (backoff fixo) e {@link
 * DeadLetterPublishingRecoverer} enviando para {@code <topico>-dlq}. Eventos invalidos ({@link
 * EventoInvalidoException}) sao tratados como nao retentaveis: vao direto para a DLQ, sem retry e
 * sem chamada ao gateway.
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
            (record, ex) -> new TopicPartition(record.topic() + "-dlq", record.partition()));
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(EventoInvalidoException.class);
    return handler;
  }
}
