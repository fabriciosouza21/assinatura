package com.globo.assinatura.shared.outbox.api;

import com.globo.assinatura.shared.outbox.OutboxEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Item da listagem de eventos da outbox em falha, com os campos de diagnostico da recuperacao.
 *
 * @param eventId identificador do evento, usado na retomada manual
 * @param eventType tipo do evento
 * @param falhouEm instante em que as tentativas de publicacao se esgotaram
 * @param ciclosRecuperacao numero de ciclos de recuperacao automatica ja realizados
 */
public record OutboxFalhaItem(
    UUID eventId, String eventType, Instant falhouEm, int ciclosRecuperacao) {

  /**
   * Converte um evento da outbox em item de listagem.
   *
   * @param evento evento em falha
   * @return item com os campos de diagnostico do evento
   */
  public static OutboxFalhaItem of(OutboxEvent evento) {
    return new OutboxFalhaItem(
        evento.getEventId(),
        evento.getEventType(),
        evento.getFalhouEm(),
        evento.getCiclosRecuperacao());
  }
}
