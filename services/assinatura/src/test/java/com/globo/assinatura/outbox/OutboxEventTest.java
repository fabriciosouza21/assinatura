package com.globo.assinatura.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

  private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID AGGREGATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  @DisplayName("Deve marcar evento pendente como publicado")
  void deveMarcarEventoPendenteComoPublicado() {
    OutboxEvent evento =
        OutboxEvent.criar(EVENT_ID, "Assinatura", AGGREGATE_ID, "AssinaturaSolicitada", "{}");

    evento.marcarPublicado(Instant.parse("2026-07-30T10:00:00Z"));

    assertThat(evento.getStatus())
        .as("Status transita para PUBLICADO apos confirmacao do Kafka")
        .isEqualTo(OutboxStatus.PUBLICADO);
  }
}
