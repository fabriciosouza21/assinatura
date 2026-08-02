package com.globo.assinatura.outbox;

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
 * <p>Garante que a migracao {@code V5__cria_tabela_outbox} cria a tabela {@code outbox} alinhada ao
 * agregado {@link OutboxEvent} e que o indice de polling parcial cobre os eventos pendentes. Usa o
 * Postgres do docker-compose (porta 5433) e delega o schema ao Flyway.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class OutboxRepositoryTest {

  @Autowired private OutboxRepository outboxRepository;

  @Test
  @DisplayName("Deve persistir evento pendente e gerar id")
  void devePersistirEventoPendente() {
    OutboxEvent salvo =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Assinatura",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "AssinaturaSolicitada",
                "{}"));

    assertThat(salvo.getEventId()).as("EventId persistido").isNotNull();
    assertThat(salvo.getStatus()).as("Status inicial pendente").isEqualTo(OutboxStatus.PENDENTE);
  }

  @Test
  @DisplayName("Deve selecionar apenas eventos pendentes prontos para envio")
  void deveSelecionarApenasPendentesProntos() {
    OutboxEvent pendente =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Assinatura",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "AssinaturaSolicitada",
                "{}"));
    OutboxEvent publicado =
        outboxRepository.saveAndFlush(
            OutboxEvent.criar(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "Assinatura",
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "AssinaturaSolicitada",
                "{}"));
    publicado.marcarPublicado(Instant.now());
    outboxRepository.saveAndFlush(publicado);

    List<OutboxEvent> publicaveis = outboxRepository.buscarPublicaveis(Instant.now(), 10);

    assertThat(publicaveis)
        .as("O evento pendente e selecionado")
        .extracting(OutboxEvent::getEventId)
        .contains(pendente.getEventId());
    assertThat(publicaveis)
        .as("O evento publicado nao e selecionado")
        .extracting(OutboxEvent::getEventId)
        .doesNotContain(publicado.getEventId());
  }
}
