package com.globo.assinatura.shared.outbox;

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

  @Test
  @DisplayName("Deve registrar o instante de publicacao ao marcar como publicado")
  void deveRegistrarInstanteDePublicacao() {
    OutboxEvent evento =
        OutboxEvent.criar(EVENT_ID, "Assinatura", AGGREGATE_ID, "AssinaturaSolicitada", "{}");
    Instant publicadoEm = Instant.parse("2026-07-30T10:00:00Z");

    evento.marcarPublicado(publicadoEm);

    assertThat(evento.getPublicadoEm())
        .as("Instante da confirmacao registrado em publicadoEm")
        .isEqualTo(publicadoEm);
  }

  @Test
  @DisplayName("Deve registrar falha incrementando tentativas e mantendo pendente")
  void deveRegistrarFalhaIncrementandoTentativas() {
    OutboxEvent evento =
        OutboxEvent.criar(EVENT_ID, "Assinatura", AGGREGATE_ID, "AssinaturaSolicitada", "{}");

    evento.registrarFalha("timeout", Instant.parse("2026-07-30T10:00:05Z"));

    assertThat(evento.getTentativas()).as("Tentativas incrementadas").isEqualTo(1);
  }

  @Test
  @DisplayName("Deve marcar como falha ao esgotar tentativas")
  void deveMarcarComoFalhaAoEsgotarTentativas() {
    OutboxEvent evento =
        OutboxEvent.criar(EVENT_ID, "Assinatura", AGGREGATE_ID, "AssinaturaSolicitada", "{}");

    evento.marcarFalha("timeout", Instant.parse("2026-07-30T10:01:00Z"));

    assertThat(evento.getStatus())
        .as("Status transita para FALHA apos esgotar tentativas")
        .isEqualTo(OutboxStatus.FALHA);
  }

  @Test
  @DisplayName("Deve marcar como retentativa de DLQ ao recuperar evento em falha")
  void deveMarcarComoRetentativaDlqAoRecuperar() {
    OutboxEvent evento =
        OutboxEvent.criar(EVENT_ID, "Assinatura", AGGREGATE_ID, "AssinaturaSolicitada", "{}");
    evento.marcarFalha("timeout", Instant.parse("2026-07-30T10:01:00Z"));

    evento.recuperarParaRetentativa(Instant.parse("2026-07-30T11:01:00Z"));

    assertThat(evento.getStatus())
        .as("Status transita para RETENTATIVA_DLQ apos recuperacao automatica")
        .isEqualTo(OutboxStatus.RETENTATIVA_DLQ);
  }
}
