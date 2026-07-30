package com.globo.pagamento.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declaracao dos topicos Kafka consumidos pelo Pagamento Service.
 *
 * <p>Garante a existencia do topico {@code assinatura-solicitada} e de sua DLQ {@code
 * assinatura-solicitada-dlq}, para nao depender de auto-create do broker.
 */
@Configuration
public class KafkaTopicsConfig {

  /**
   * Declara o topico consumido.
   *
   * @return topico {@code assinatura-solicitada}
   */
  @Bean
  public NewTopic assinaturaSolicitada() {
    return TopicBuilder.name("assinatura-solicitada").build();
  }

  /**
   * Declara a DLQ do topico consumido.
   *
   * @return topico {@code assinatura-solicitada-dlq}
   */
  @Bean
  public NewTopic assinaturaSolicitadaDlq() {
    return TopicBuilder.name("assinatura-solicitada-dlq").build();
  }
}
