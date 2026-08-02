package com.globo.pagamento.outbox;

import static org.assertj.core.api.Assertions.assertThat;

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
}
