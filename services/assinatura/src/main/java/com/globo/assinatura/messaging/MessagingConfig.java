package com.globo.assinatura.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/**
 * Configuracao de infraestrutura Kafka do servico de assinatura.
 *
 * <p>Registra os topicos de status de pagamento e sua DLQ, e configura a fabrica de containers do
 * listener com tratamento de erros: retentativas com backoff fixo para falhas transientes e envio
 * direto para a DLQ para erros de desserializacao (payload sempre invalido).
 */
@Configuration
public class MessagingConfig {

  /**
   * Cria o topico de status de pagamento atualizado.
   *
   * @param nome nome do topico definido em {@code app.kafka.topico-pagamento-status-atualizado}
   * @return topico com 1 particao e fator de replicacao 1
   */
  @Bean
  public NewTopic topicoPagamentoStatusAtualizado(
      @Value("${app.kafka.topico-pagamento-status-atualizado}") String nome) {
    return new NewTopic(nome, 1, (short) 1);
  }

  /**
   * Cria o topico DLQ de status de pagamento atualizado.
   *
   * @param nome nome do topico definido em {@code app.kafka.topico-pagamento-status-atualizado-dlq}
   * @return topico com 1 particao e fator de replicacao 1
   */
  @Bean
  public NewTopic topicoPagamentoStatusAtualizadoDlq(
      @Value("${app.kafka.topico-pagamento-status-atualizado-dlq}") String nome) {
    return new NewTopic(nome, 1, (short) 1);
  }

  /**
   * Configura a fabrica de containers do listener Kafka com tratamento de erros.
   *
   * <p>Aplica um {@link DefaultErrorHandler} com:
   *
   * <ul>
   *   <li>retentativas com backoff fixo de 1s entre tentativas, totalizando 3 tentativas (1 inicial
   *       + 2 retentativas);
   *   <li>envio para o topico {@code <topico>-dlq} quando esgotadas as retentativas;
   *   <li>{@link JacksonException} como nao retentavel, enviando erros de desserializacao direto
   *       para a DLQ.
   * </ul>
   *
   * @param consumerFactory fabrica de consumidores injetada pelo Spring
   * @param kafkaTemplate template Kafka injetado pelo Spring, usado para publicar na DLQ
   * @return fabrica de containers configurada
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> new TopicPartition(record.topic() + "-dlq", record.partition()));
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    errorHandler.addNotRetryableExceptions(JacksonException.class);
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
