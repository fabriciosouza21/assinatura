package com.globo.pagamento.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Teste unitario da declaracao dos topicos Kafka do Pagamento Service. */
class KafkaTopicsConfigTest {

  private final KafkaTopicsConfig config = new KafkaTopicsConfig();

  @Test
  @DisplayName("Deve declarar os topicos de cancelamento e suas DLQs")
  void deveDeclararTopicosDeCancelamentoComDlqs() {
    NewTopic cancelamentoAgendado = config.cancelamentoAgendado();
    NewTopic cancelamentoAgendadoDlq = config.cancelamentoAgendadoDlq();
    NewTopic assinaturaCancelada = config.assinaturaCancelada();
    NewTopic assinaturaCanceladaDlq = config.assinaturaCanceladaDlq();

    assertThat(cancelamentoAgendado.name())
        .as("Topico de cancelamento agendado")
        .isEqualTo("cancelamento-agendado");
    assertThat(cancelamentoAgendadoDlq.name())
        .as("DLQ de cancelamento agendado")
        .isEqualTo("cancelamento-agendado-dlq");
    assertThat(assinaturaCancelada.name())
        .as("Topico de assinatura cancelada")
        .isEqualTo("assinatura-cancelada");
    assertThat(assinaturaCanceladaDlq.name())
        .as("DLQ de assinatura cancelada")
        .isEqualTo("assinatura-cancelada-dlq");
  }

  @Test
  @DisplayName("Deve declarar particoes, replicacao e retencao explicitas nas DLQs")
  void deveDeclararConfigExplicitaNasDlqs() {
    assertThat(config.assinaturaSolicitadaDlq().numPartitions())
        .as("Particoes da DLQ de assinatura solicitada")
        .isEqualTo(3);
    assertThat(config.assinaturaSolicitadaDlq().replicationFactor())
        .as("Replicacao da DLQ de assinatura solicitada")
        .isEqualTo((short) 1);
    assertThat(config.assinaturaSolicitadaDlq().configs())
        .as("Retencao da DLQ de assinatura solicitada")
        .containsEntry("retention.ms", "604800000");
    assertThat(config.renovacaoSolicitadaDlq().numPartitions())
        .as("Particoes da DLQ de renovacao solicitada")
        .isEqualTo(3);
    assertThat(config.renovacaoSolicitadaDlq().replicationFactor())
        .as("Replicacao da DLQ de renovacao solicitada")
        .isEqualTo((short) 1);
    assertThat(config.renovacaoSolicitadaDlq().configs())
        .as("Retencao da DLQ de renovacao solicitada")
        .containsEntry("retention.ms", "604800000");
    assertThat(config.renovacaoResultadoDlq().numPartitions())
        .as("Particoes da DLQ de resultado de renovacao")
        .isEqualTo(3);
    assertThat(config.renovacaoResultadoDlq().replicationFactor())
        .as("Replicacao da DLQ de resultado de renovacao")
        .isEqualTo((short) 1);
    assertThat(config.renovacaoResultadoDlq().configs())
        .as("Retencao da DLQ de resultado de renovacao")
        .containsEntry("retention.ms", "604800000");
    assertThat(config.cancelamentoAgendadoDlq().numPartitions())
        .as("Particoes da DLQ de cancelamento agendado")
        .isEqualTo(3);
    assertThat(config.cancelamentoAgendadoDlq().replicationFactor())
        .as("Replicacao da DLQ de cancelamento agendado")
        .isEqualTo((short) 1);
    assertThat(config.cancelamentoAgendadoDlq().configs())
        .as("Retencao da DLQ de cancelamento agendado")
        .containsEntry("retention.ms", "604800000");
    assertThat(config.assinaturaCanceladaDlq().numPartitions())
        .as("Particoes da DLQ de assinatura cancelada")
        .isEqualTo(3);
    assertThat(config.assinaturaCanceladaDlq().replicationFactor())
        .as("Replicacao da DLQ de assinatura cancelada")
        .isEqualTo((short) 1);
    assertThat(config.assinaturaCanceladaDlq().configs())
        .as("Retencao da DLQ de assinatura cancelada")
        .containsEntry("retention.ms", "604800000");
  }
}
