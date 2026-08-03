package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetomarEventoOutboxTest {

  @Mock private OutboxRepository outboxRepository;

  private RetomarEventoOutbox retomar;

  @BeforeEach
  void setUp() {
    retomar =
        new RetomarEventoOutbox(
            outboxRepository, new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMillis(500)));
  }

  @Test
  @DisplayName("Deve retomar evento em falha para retentativa de DLQ")
  void deveRetomarEventoEmFalhaParaRetentativaDlq() {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    OutboxEvent evento = eventoEmFalha(eventId);
    when(outboxRepository.buscarPorIdComLock(eventId)).thenReturn(Optional.of(evento));

    retomar.executar(eventId, "admin");

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    assertThat(capturado.getValue().getStatus())
        .as("Evento retomado transita para RETENTATIVA_DLQ")
        .isEqualTo(OutboxStatus.RETENTATIVA_DLQ);
    assertThat(capturado.getValue().getTentativas())
        .as("Tentativas zeradas para a politica de retry rodar completa")
        .isZero();
    assertThat(capturado.getValue().getCiclosRecuperacao())
        .as("Contador de ciclos de recuperacao incrementado")
        .isEqualTo(1);
    assertThat(capturado.getValue().getProximaTentativaEm())
        .as("Proxima tentativa proxima de agora mais o backoff inicial")
        .isCloseTo(Instant.now().plusSeconds(1), byLessThan(2, ChronoUnit.SECONDS));
  }

  private static OutboxEvent eventoEmFalha(UUID eventId) {
    OutboxEvent evento =
        OutboxEvent.criar(
            eventId,
            "Assinatura",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "AssinaturaSolicitada",
            "{}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    return evento;
  }
}
