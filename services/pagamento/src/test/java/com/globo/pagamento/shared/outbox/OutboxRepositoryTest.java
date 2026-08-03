package com.globo.pagamento.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link OutboxRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao {@code V7__cria_tabela_outbox} cria a tabela {@code outbox} alinhada ao
 * agregado {@link OutboxEvent} e que a busca de eventos publicaveis considera apenas pendentes com
 * a proxima tentativa vencida. Usa o Postgres do docker-compose (porta 5433) e delega o schema ao
 * Flyway.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class OutboxRepositoryTest {

  @Autowired private OutboxRepository outboxRepository;

  @Test
  @DisplayName("Deve persistir evento pendente com id gerado pelo produtor")
  void devePersistirEventoPendente() {
    UUID eventId = UUID.randomUUID();
    OutboxEvent salvo =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                eventId, "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}"));

    assertThat(salvo.getEventId()).as("EventId persistido").isEqualTo(eventId);
    assertThat(salvo.getStatus()).as("Status inicial pendente").isEqualTo(OutboxStatus.PENDENTE);
  }

  @Test
  @DisplayName("Deve selecionar apenas eventos pendentes prontos para envio")
  void deveSelecionarApenasPendentesProntos() {
    OutboxEvent pendente =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    OutboxEvent publicado =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    publicado.marcarPublicado(Instant.now());
    outboxRepository.saveAndFlush(publicado);

    List<OutboxEvent> publicaveis = outboxRepository.buscarPublicaveis(Instant.now(), 10);

    assertThat(publicaveis)
        .as("Apenas o evento pendente e selecionado")
        .extracting(OutboxEvent::getEventId)
        .containsExactly(pendente.getEventId());
  }

  @Test
  @DisplayName("Deve selecionar evento em falha ha mais tempo que o timeout da DLQ")
  void deveSelecionarEventoEmFalhaVencido() {
    OutboxEvent falha =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    falha.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    outboxRepository.saveAndFlush(falha);

    List<OutboxEvent> recuperaveis =
        outboxRepository.buscarRecuperaveis(Instant.now().minusSeconds(3600), 3, 10);

    assertThat(recuperaveis)
        .as("Evento em FALHA ha mais de 1h e elegivel para recuperacao")
        .extracting(OutboxEvent::getEventId)
        .contains(falha.getEventId());
  }

  @Test
  @DisplayName("Deve selecionar tambem eventos em retentativa de DLQ prontos para envio")
  void deveSelecionarEventosEmRetentativaDlq() {
    OutboxEvent retentativa =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    retentativa.marcarFalha("timeout", Instant.now());
    retentativa.recuperarParaRetentativa(Instant.now());
    outboxRepository.saveAndFlush(retentativa);

    List<OutboxEvent> publicaveis = outboxRepository.buscarPublicaveis(Instant.now(), 10);

    assertThat(publicaveis)
        .as("Evento em RETENTATIVA_DLQ tambem e elegivel para publicacao")
        .extracting(OutboxEvent::getEventId)
        .contains(retentativa.getEventId());
  }

  @Test
  @DisplayName("Nao deve selecionar evento em falha que ainda nao venceu o timeout da DLQ")
  void naoDeveSelecionarEventoEmFalhaRecente() {
    OutboxEvent falha =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    falha.marcarFalha("timeout", Instant.now().minusSeconds(600));
    outboxRepository.saveAndFlush(falha);

    List<OutboxEvent> recuperaveis =
        outboxRepository.buscarRecuperaveis(Instant.now().minusSeconds(3600), 3, 10);

    assertThat(recuperaveis)
        .as("Evento em FALHA ha menos de 1h nao e elegivel para recuperacao")
        .isEmpty();
  }

  @Test
  @DisplayName("Deve contar eventos agrupados por status monitorado")
  void deveContarEventosPorStatus() {
    final Map<String, Long> antes = contagensPorStatus();
    outboxRepository.saveAndFlush(
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}"));
    outboxRepository.saveAndFlush(
        OutboxEvent.criar(
            UUID.randomUUID(), "Cobranca", UUID.randomUUID(), "PagamentoStatusAtualizado", "{}"));
    OutboxEvent retentativa =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    retentativa.marcarFalha("timeout", Instant.now());
    retentativa.recuperarParaRetentativa(Instant.now());
    outboxRepository.saveAndFlush(retentativa);
    OutboxEvent falha =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    falha.marcarFalha("timeout", Instant.now());
    outboxRepository.saveAndFlush(falha);
    OutboxEvent publicado =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    publicado.marcarPublicado(Instant.now());
    outboxRepository.saveAndFlush(publicado);

    assertThat(deltaEntre(contagensPorStatus(), antes))
        .as("Incremento de contagem por status monitorado apos os eventos criados no teste")
        .containsOnly(
            Map.entry("PENDENTE", 2L), Map.entry("RETENTATIVA_DLQ", 1L), Map.entry("FALHA", 1L));
  }

  private Map<String, Long> contagensPorStatus() {
    return outboxRepository.contarPorStatus().stream()
        .collect(
            Collectors.toMap(linha -> (String) linha[0], linha -> ((Number) linha[1]).longValue()));
  }

  private static Map<String, Long> deltaEntre(Map<String, Long> depois, Map<String, Long> antes) {
    Map<String, Long> delta = new HashMap<>(depois);
    antes.forEach((status, total) -> delta.merge(status, -total, Long::sum));
    delta.values().removeIf(total -> total == 0L);
    return delta;
  }

  @Test
  @DisplayName("Nao deve selecionar evento que esgotou os ciclos de recuperacao")
  void naoDeveSelecionarEventoComCiclosEsgotados() {
    OutboxEvent falha =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.randomUUID(),
                "Cobranca",
                UUID.randomUUID(),
                "PagamentoStatusAtualizado",
                "{}"));
    falha.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    falha.recuperarParaRetentativa(Instant.now().minusSeconds(3700));
    falha.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    outboxRepository.saveAndFlush(falha);

    List<OutboxEvent> recuperaveis =
        outboxRepository.buscarRecuperaveis(Instant.now().minusSeconds(3600), 1, 10);

    assertThat(recuperaveis)
        .as("Evento com 1 ciclo usado nao e elegivel quando maxCiclos e 1")
        .isEmpty();
  }
}
