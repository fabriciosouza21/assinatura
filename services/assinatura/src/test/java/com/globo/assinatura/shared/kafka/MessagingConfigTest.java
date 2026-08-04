package com.globo.assinatura.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessagingConfigTest {

  @Test
  @DisplayName("Deve declarar os topicos Kafka dos eventos de cancelamento")
  void deveDeclararTopicosKafkaDosEventosDeCancelamento() {
    RotasEventoTopicoProperties rotas =
        new RotasEventoTopicoProperties(
            Map.of(
                "CancelamentoAgendado", "cancelamento-agendado",
                "AssinaturaCancelada", "assinatura-cancelada"));
    MessagingConfig config = new MessagingConfig();

    NewTopic cancelamentoAgendado = config.topicoCancelamentoAgendado(rotas);
    NewTopic assinaturaCancelada = config.topicoAssinaturaCancelada(rotas);

    assertThat(cancelamentoAgendado.name())
        .as("Nome do topico de cancelamento agendado")
        .isEqualTo("cancelamento-agendado");
    assertThat(cancelamentoAgendado.numPartitions())
        .as("Particoes do topico de cancelamento agendado")
        .isEqualTo(1);
    assertThat(cancelamentoAgendado.replicationFactor())
        .as("Replicacao do topico de cancelamento agendado")
        .isEqualTo((short) 1);
    assertThat(assinaturaCancelada.name())
        .as("Nome do topico de assinatura cancelada")
        .isEqualTo("assinatura-cancelada");
    assertThat(assinaturaCancelada.numPartitions())
        .as("Particoes do topico de assinatura cancelada")
        .isEqualTo(1);
    assertThat(assinaturaCancelada.replicationFactor())
        .as("Replicacao do topico de assinatura cancelada")
        .isEqualTo((short) 1);
  }

  @Test
  @DisplayName("Deve declarar particoes, replicacao e retencao explicitas nas DLQs")
  void deveDeclararConfigExplicitaNasDlqs() {
    MessagingConfig config = new MessagingConfig();

    NewTopic pagamentoStatusAtualizadoDlq =
        config.topicoPagamentoStatusAtualizadoDlq("pagamento-status-atualizado-dlq");
    NewTopic renovacaoResultadoDlq = config.topicoRenovacaoResultadoDlq("renovacao-resultado-dlq");

    assertThat(pagamentoStatusAtualizadoDlq.numPartitions())
        .as("Particoes da DLQ de status de pagamento")
        .isEqualTo(3);
    assertThat(pagamentoStatusAtualizadoDlq.replicationFactor())
        .as("Replicacao da DLQ de status de pagamento")
        .isEqualTo((short) 1);
    assertThat(pagamentoStatusAtualizadoDlq.configs())
        .as("Retencao da DLQ de status de pagamento")
        .containsEntry("retention.ms", "604800000");
    assertThat(renovacaoResultadoDlq.numPartitions())
        .as("Particoes da DLQ de resultado de renovacao")
        .isEqualTo(3);
    assertThat(renovacaoResultadoDlq.replicationFactor())
        .as("Replicacao da DLQ de resultado de renovacao")
        .isEqualTo((short) 1);
    assertThat(renovacaoResultadoDlq.configs())
        .as("Retencao da DLQ de resultado de renovacao")
        .containsEntry("retention.ms", "604800000");
  }
}
