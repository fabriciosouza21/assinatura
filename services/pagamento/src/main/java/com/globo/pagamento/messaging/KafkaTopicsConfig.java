package com.globo.pagamento.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declaracao dos topicos Kafka do Pagamento Service.
 *
 * <p>Garante a existencia do topico consumido {@code assinatura-solicitada} e de sua DLQ {@code
 * assinatura-solicitada-dlq}, alem do topico produzido {@code pagamento-status-atualizado}, para
 * nao depender de auto-create do broker.
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

  /**
   * Declara o topico produzido com o status de pagamento normalizado.
   *
   * @return topico {@code pagamento-status-atualizado}
   */
  @Bean
  public NewTopic pagamentoStatusAtualizado() {
    return TopicBuilder.name("pagamento-status-atualizado").build();
  }
}
