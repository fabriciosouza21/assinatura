package com.globo.pagamento.messaging;

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
}
