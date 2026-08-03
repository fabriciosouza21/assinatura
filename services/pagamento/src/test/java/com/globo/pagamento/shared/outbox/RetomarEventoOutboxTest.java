package com.globo.pagamento.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.byLessThan;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

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

  @Test
  @DisplayName("Deve lancar excecao de nao encontrado para eventId inexistente")
  void deveLancarExcecaoParaEventIdInexistente() {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(outboxRepository.buscarPorIdComLock(eventId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> retomar.executar(eventId, "admin"))
        .as("Retomada de evento inexistente rejeitada")
        .isInstanceOf(OutboxEventoNaoEncontradoException.class);

    verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Deve rejeitar retomada de evento que nao esta em falha")
  void deveRejeitarRetomadaDeEventoForaDeFalha() {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    OutboxEvent evento = OutboxEvent.criar(eventId, "Pagamento", UUID.randomUUID(), "Evento", "{}");
    when(outboxRepository.buscarPorIdComLock(eventId)).thenReturn(Optional.of(evento));

    assertThatThrownBy(() -> retomar.executar(eventId, "admin"))
        .as("Retomada de evento fora de FALHA rejeitada")
        .isInstanceOf(OutboxEventoNaoRetomavelException.class);

    verify(outboxRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Deve registrar auditoria da retomada manual")
  void deveRegistrarAuditoriaDaRetomadaManual() {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    OutboxEvent evento = eventoEmFalha(eventId);
    when(outboxRepository.buscarPorIdComLock(eventId)).thenReturn(Optional.of(evento));
    Logger logger = (Logger) LoggerFactory.getLogger(RetomarEventoOutbox.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      retomar.executar(eventId, "admin@example.com");
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .as("log de auditoria da retomada manual")
        .anyMatch(
            eventoLog ->
                eventoLog.getLevel() == Level.INFO
                    && "outbox_falha_retomada_manual"
                        .equals(
                            eventoLog.getKeyValuePairs().stream()
                                .filter(par -> "event".equals(par.key))
                                .map(par -> par.value.toString())
                                .findFirst()
                                .orElse(null))
                    && eventId
                        .toString()
                        .equals(
                            eventoLog.getKeyValuePairs().stream()
                                .filter(par -> "eventId".equals(par.key))
                                .map(par -> par.value.toString())
                                .findFirst()
                                .orElse(null))
                    && "PagamentoStatusAtualizado"
                        .equals(
                            eventoLog.getKeyValuePairs().stream()
                                .filter(par -> "eventType".equals(par.key))
                                .map(par -> par.value.toString())
                                .findFirst()
                                .orElse(null))
                    && "admin@example.com"
                        .equals(
                            eventoLog.getKeyValuePairs().stream()
                                .filter(par -> "adminId".equals(par.key))
                                .map(par -> par.value.toString())
                                .findFirst()
                                .orElse(null)));
  }

  private static OutboxEvent eventoEmFalha(UUID eventId) {
    OutboxEvent evento =
        OutboxEvent.criar(
            eventId,
            "Pagamento",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "PagamentoStatusAtualizado",
            "{}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    return evento;
  }
}
