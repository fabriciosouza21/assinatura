package com.globo.pagamento.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
}
