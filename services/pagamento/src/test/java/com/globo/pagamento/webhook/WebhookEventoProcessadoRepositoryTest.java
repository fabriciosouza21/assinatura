package com.globo.pagamento.webhook;

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
 * Teste de integracao do {@link WebhookEventoProcessadoRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao cria a tabela de dedup, que o agregado {@link WebhookEventoProcessado}
 * persiste alinhado ao schema e que a busca por {@code eventId} sustenta a idempotencia ponta a
 * ponta do webhook. Usa o Postgres do docker-compose (porta 5433, banco {@code pagamento}).
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
class WebhookEventoProcessadoRepositoryTest {

  @Autowired private WebhookEventoProcessadoRepository repository;

  @Test
  @DisplayName("Deve registrar evento processado e marca-lo como existente por eventId")
  void deveIndicarEventoRegistradoComoExistente() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    UUID assinaturaId = UUID.fromString("00000000-0000-0000-0000-000000000011");

    assertThat(repository.existsByEventId(eventId)).as("Evento ainda nao registrado").isFalse();

    repository.save(new WebhookEventoProcessado(eventId, assinaturaId));
    repository.flush();

    assertThat(repository.existsByEventId(eventId)).as("Evento registrado apos o save").isTrue();
  }
}
