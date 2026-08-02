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
}
