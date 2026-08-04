package com.globo.pagamento.shared.kafka;

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

  private static final int PARTICOES_DLQ = 3;
  private static final int REPLICACAO_DLQ = 1;
  private static final String RETENCAO_DLQ_MS = "604800000";

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
   * @return topico {@code assinatura-solicitada-dlq} com 3 particoes, fator de replicacao 1 e
   *     retencao de 7 dias
   */
  @Bean
  public NewTopic assinaturaSolicitadaDlq() {
    return TopicBuilder.name("assinatura-solicitada-dlq")
        .partitions(PARTICOES_DLQ)
        .replicas(REPLICACAO_DLQ)
        .config("retention.ms", RETENCAO_DLQ_MS)
        .build();
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
   * @return topico {@code renovacao-solicitada-dlq} com 3 particoes, fator de replicacao 1 e
   *     retencao de 7 dias
   */
  @Bean
  public NewTopic renovacaoSolicitadaDlq() {
    return TopicBuilder.name("renovacao-solicitada-dlq")
        .partitions(PARTICOES_DLQ)
        .replicas(REPLICACAO_DLQ)
        .config("retention.ms", RETENCAO_DLQ_MS)
        .build();
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
   * @return topico {@code renovacao-resultado-dlq} com 3 particoes, fator de replicacao 1 e
   *     retencao de 7 dias
   */
  @Bean
  public NewTopic renovacaoResultadoDlq() {
    return TopicBuilder.name("renovacao-resultado-dlq")
        .partitions(PARTICOES_DLQ)
        .replicas(REPLICACAO_DLQ)
        .config("retention.ms", RETENCAO_DLQ_MS)
        .build();
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
   * @return topico {@code cancelamento-agendado-dlq} com 3 particoes, fator de replicacao 1 e
   *     retencao de 7 dias
   */
  @Bean
  public NewTopic cancelamentoAgendadoDlq() {
    return TopicBuilder.name("cancelamento-agendado-dlq")
        .partitions(PARTICOES_DLQ)
        .replicas(REPLICACAO_DLQ)
        .config("retention.ms", RETENCAO_DLQ_MS)
        .build();
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
   * @return topico {@code assinatura-cancelada-dlq} com 3 particoes, fator de replicacao 1 e
   *     retencao de 7 dias
   */
  @Bean
  public NewTopic assinaturaCanceladaDlq() {
    return TopicBuilder.name("assinatura-cancelada-dlq")
        .partitions(PARTICOES_DLQ)
        .replicas(REPLICACAO_DLQ)
        .config("retention.ms", RETENCAO_DLQ_MS)
        .build();
  }
}
