package com.globo.pagamento.webhook.idempotencia;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link WebhookEventoProcessado}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface WebhookEventoProcessadoRepository
    extends JpaRepository<WebhookEventoProcessado, Long> {

  /**
   * Verifica se um evento de webhook ja foi processado.
   *
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook)
   * @return {@code true} se o evento ja tiver sido processado; {@code false} caso contrario
   */
  boolean existsByEventId(UUID eventId);
}
