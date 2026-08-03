package com.globo.pagamento.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  @Test
  @DisplayName("Deve buscar recuperaveis com o timeout, max de ciclos e lote configurados")
  void deveBuscarComParametrosConfigurados() {
    when(outboxRepository.buscarRecuperaveis(any(Instant.class), anyInt(), anyInt()))
        .thenReturn(List.of());

    scheduler.recuperarFalhas();

    ArgumentCaptor<Instant> limiteCapturado = ArgumentCaptor.forClass(Instant.class);
    verify(outboxRepository).buscarRecuperaveis(limiteCapturado.capture(), eq(3), eq(100));
    assertThat(limiteCapturado.getValue())
        .as("Limite e aproximadamente agora menos o timeout de 3600s")
        .isCloseTo(Instant.now().minusSeconds(3600), byLessThan(2, ChronoUnit.SECONDS));
  }

  private static OutboxEvent eventoEmFalha() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    return evento;
  }
}
