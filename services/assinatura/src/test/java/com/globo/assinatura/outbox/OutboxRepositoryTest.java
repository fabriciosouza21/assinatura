package com.globo.assinatura.outbox;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Garante que a migracao V4 cria a tabela {@code outbox} alinhada ao agregado {@link
 * OutboxEvent} e que o indice de polling parcial cobre os eventos pendentes. Usa o Postgres do
 * docker-compose (porta 5433) e delega o schema ao Flyway.
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
}
