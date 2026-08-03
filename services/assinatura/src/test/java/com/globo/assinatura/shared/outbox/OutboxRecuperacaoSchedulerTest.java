package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxRecuperacaoSchedulerTest {

  @Mock private OutboxRepository outboxRepository;

  private OutboxRecuperacaoScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new OutboxRecuperacaoScheduler(outboxRepository, 3600L, 3, 100);
  }

  @Test
  @DisplayName("Deve promover evento recuperavel para retentativa de DLQ")
  void devePromoverEventoRecuperavelParaRetentativaDlq() {
    OutboxEvent evento = eventoEmFalha();
    when(outboxRepository.buscarRecuperaveis(any(Instant.class), anyInt(), anyInt()))
        .thenReturn(List.of(evento));

    scheduler.recuperarFalhas();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    assertThat(capturado.getValue().getStatus())
        .as("Evento recuperado transita para RETENTATIVA_DLQ")
        .isEqualTo(OutboxStatus.RETENTATIVA_DLQ);
  }

  private static OutboxEvent eventoEmFalha() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "Assinatura",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "AssinaturaSolicitada",
            "{}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    return evento;
  }
}
