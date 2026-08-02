package com.globo.pagamento.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

  @Test
  @DisplayName("Deve criar evento pendente para publicacao")
  void deveCriarEventoPendente() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    assertThat(evento.getStatus()).as("Status do evento criado").isEqualTo(OutboxStatus.PENDENTE);
  }

  @Test
  @DisplayName("Deve marcar evento como publicado")
  void deveMarcarEventoComoPublicado() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.marcarPublicado(Instant.now());

    assertThat(evento.getStatus())
        .as("Status apos marcar como publicado")
        .isEqualTo(OutboxStatus.PUBLICADO);
  }
}
