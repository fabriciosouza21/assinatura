package com.globo.pagamento.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declaracao dos topicos Kafka do Pagamento Service.
 *
 * <p>Garante a existencia dos topicos consumidos ({@code assinatura-solicitada} e {@code
 * renovacao-solicitada}, {@code cancelamento-agendado} e {@code assinatura-cancelada}) e de suas
 * DLQs, alem dos topicos produzidos ({@code pagamento-status-atualizado} e {@code
 * renovacao-resultado}), para nao depender de auto-create do broker.
 */
@Configuration
public class KafkaTopicsConfig {

  /**
   * Declara o topico consumido da adesao.
   *
   * @return topico {@code assinatura-solicitada}
   */
  @Bean
  public NewTopic assinaturaSolicitada() {
    return TopicBuilder.name("assinatura-solicitada").build();
  }

  /**
   * Declara a DLQ do topico consumido da adesao.
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

  /**
   * Declara o topico consumido da renovacao.
   *
   * @return topico {@code renovacao-solicitada}
   */
  @Bean
  public NewTopic renovacaoSolicitada() {
    return TopicBuilder.name("renovacao-solicitada").build();
  }

  /**
   * Declara a DLQ do topico consumido da renovacao.
   *
   * @return topico {@code renovacao-solicitada-dlq}
   */
  @Bean
  public NewTopic renovacaoSolicitadaDlq() {
    return TopicBuilder.name("renovacao-solicitada-dlq").build();
  }

  /**
   * Declara o topico produzido com o resultado da renovacao.
   *
   * @return topico {@code renovacao-resultado}
   */
  @Bean
  public NewTopic renovacaoResultado() {
    return TopicBuilder.name("renovacao-resultado").build();
  }

  /**
   * Declara a DLQ do topico produzido com o resultado da renovacao.
   *
   * @return topico {@code renovacao-resultado-dlq}
   */
  @Bean
  public NewTopic renovacaoResultadoDlq() {
    return TopicBuilder.name("renovacao-resultado-dlq").build();
  }

  /**
   * Declara o topico consumido de cancelamento agendado.
   *
   * @return topico {@code cancelamento-agendado}
   */
  @Bean
  public NewTopic cancelamentoAgendado() {
    return TopicBuilder.name("cancelamento-agendado").build();
  }

  /**
   * Declara a DLQ do topico consumido de cancelamento agendado.
   *
   * @return topico {@code cancelamento-agendado-dlq}
   */
  @Bean
  public NewTopic cancelamentoAgendadoDlq() {
    return TopicBuilder.name("cancelamento-agendado-dlq").build();
  }

  /**
   * Declara o topico consumido de assinatura cancelada.
   *
   * @return topico {@code assinatura-cancelada}
   */
  @Bean
  public NewTopic assinaturaCancelada() {
    return TopicBuilder.name("assinatura-cancelada").build();
  }

  /**
   * Declara a DLQ do topico consumido de assinatura cancelada.
   *
   * @return topico {@code assinatura-cancelada-dlq}
   */
  @Bean
  public NewTopic assinaturaCanceladaDlq() {
    return TopicBuilder.name("assinatura-cancelada-dlq").build();
  }
}
