package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxMetricasSchedulerTest {

  @Mock private OutboxRepository outboxRepository;

  private SimpleMeterRegistry meterRegistry;
  private OutboxMetricasScheduler scheduler;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    scheduler = new OutboxMetricasScheduler(outboxRepository, meterRegistry, "assinatura");
  }

  @Test
  @DisplayName("Deve registrar gauge zerado para os tres status no arranque")
  void deveRegistrarGaugeZeradoParaOsTresStatus() {
    for (String status : List.of("pendente", "retentativa_dlq", "falha")) {
      assertThat(gaugeDe(status)).as("Gauge de %s registrado no arranque", status).isEqualTo(0.0);
    }
  }

  @Test
  @DisplayName("Deve atualizar os gauges com a contagem por status do repositorio")
  void deveAtualizarGaugesComContagemPorStatus() {
    List<OutboxRepository.ContagemStatus> contagens =
        List.of(contagem("FALHA", 3L), contagem("PENDENTE", 5L), contagem("RETENTATIVA_DLQ", 2L));
    when(outboxRepository.contarPorStatus()).thenReturn(contagens);

    scheduler.atualizarMetricas();

    assertThat(gaugeDe("falha")).as("Gauge de falha").isEqualTo(3.0);
    assertThat(gaugeDe("pendente")).as("Gauge de pendente").isEqualTo(5.0);
    assertThat(gaugeDe("retentativa_dlq")).as("Gauge de retentativa").isEqualTo(2.0);
  }

  @Test
  @DisplayName("Deve zerar status sem eventos na consulta corrente")
  void deveZerarStatusSemEventos() {
    List<OutboxRepository.ContagemStatus> contagens = List.of(contagem("FALHA", 3L));
    when(outboxRepository.contarPorStatus()).thenReturn(contagens);
    scheduler.atualizarMetricas();
    when(outboxRepository.contarPorStatus()).thenReturn(List.of());

    scheduler.atualizarMetricas();

    assertThat(gaugeDe("falha"))
        .as("Gauge zera quando nao ha mais eventos em falha")
        .isEqualTo(0.0);
    assertThat(gaugeDe("pendente")).as("Gauge permanece zerado").isEqualTo(0.0);
  }

  private Double gaugeDe(String status) {
    return meterRegistry
        .get("outbox.eventos")
        .tag("servico", "assinatura")
        .tag("status", status)
        .gauge()
        .value();
  }

  private static OutboxRepository.ContagemStatus contagem(String status, Long total) {
    OutboxRepository.ContagemStatus contagem = mock(OutboxRepository.ContagemStatus.class);
    when(contagem.getStatus()).thenReturn(status);
    when(contagem.getTotal()).thenReturn(total);
    return contagem;
  }
}
