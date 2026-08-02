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

  @Test
  @DisplayName("Deve registrar instante de publicacao")
  void deveRegistrarInstanteDePublicacao() {
    Instant publicadoEm = Instant.parse("2026-08-01T12:00:00Z");
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.marcarPublicado(publicadoEm);

    assertThat(evento.getPublicadoEm())
        .as("Instante de publicacao registrado")
        .isEqualTo(publicadoEm);
  }

  @Test
  @DisplayName("Deve registrar falha incrementando tentativas")
  void deveRegistrarFalhaIncrementandoTentativas() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.registrarFalha("erro", Instant.parse("2026-08-01T12:05:00Z"));

    assertThat(evento.getTentativas())
        .as("Contador de tentativas apos registrar falha")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Deve registrar o ultimo erro da falha")
  void deveRegistrarUltimoErro() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.registrarFalha("falha de rede", Instant.parse("2026-08-01T12:05:00Z"));

    assertThat(evento.getUltimoErro()).as("Ultimo erro registrado").isEqualTo("falha de rede");
  }

  @Test
  @DisplayName("Deve agendar a proxima tentativa de publicacao")
  void deveAgendarProximaTentativa() {
    Instant proximaTentativa = Instant.parse("2026-08-01T12:05:00Z");
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.registrarFalha("falha de rede", proximaTentativa);

    assertThat(evento.getProximaTentativaEm())
        .as("Instante agendado para a proxima tentativa")
        .isEqualTo(proximaTentativa);
  }

  @Test
  @DisplayName("Deve marcar falha definitiva")
  void deveMarcarFalhaDefinitiva() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.marcarFalha("tentativas esgotadas", Instant.parse("2026-08-01T12:10:00Z"));

    assertThat(evento.getStatus())
        .as("Status apos marcar falha definitiva")
        .isEqualTo(OutboxStatus.FALHA);
  }

  @Test
  @DisplayName("Deve registrar instante da falha definitiva")
  void deveRegistrarInstanteDaFalhaDefinitiva() {
    Instant falhouEm = Instant.parse("2026-08-01T12:10:00Z");
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}");

    evento.marcarFalha("tentativas esgotadas", falhouEm);

    assertThat(evento.getFalhouEm())
        .as("Instante em que as tentativas se esgotaram")
        .isEqualTo(falhouEm);
  }
}
