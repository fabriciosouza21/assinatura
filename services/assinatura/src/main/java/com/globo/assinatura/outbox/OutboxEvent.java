package com.globo.assinatura.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento persistido na outbox aguardando publicacao no Kafka.
 *
 * <p>Nasce em {@link OutboxStatus#PENDENTE} e transita para {@link OutboxStatus#PUBLICADO} quando o
 * Kafka confirma o envio. O {@code payload} carrega o evento serializado em JSON.
 */
public class OutboxEvent {

  private final UUID eventId;
  private final String aggregateType;
  private final UUID aggregateId;
  private final String eventType;
  private final String payload;
  private OutboxStatus status;

  private OutboxEvent(
      UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.status = OutboxStatus.PENDENTE;
  }

  /**
   * Cria um evento pendente para publicacao.
   *
   * @param eventId identificador unico do evento para deduplicacao no consumidor
   * @param aggregateType tipo do agregado de origem
   * @param aggregateId identificador publico do agregado de origem
   * @param eventType tipo do evento
   * @param payload evento serializado em JSON
   * @return evento pendente
   */
  public static OutboxEvent criar(
      UUID eventId, String aggregateType, UUID aggregateId, String eventType, String payload) {
    return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload);
  }

  /**
   * Marca o evento como publicado apos confirmacao do Kafka.
   *
   * @param publicadoEm instante da confirmacao
   */
  public void marcarPublicado(Instant publicadoEm) {
    this.status = OutboxStatus.PUBLICADO;
  }

  public OutboxStatus getStatus() {
    return status;
  }
}
