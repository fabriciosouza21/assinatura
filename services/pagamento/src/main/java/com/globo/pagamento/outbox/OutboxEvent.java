package com.globo.pagamento.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Evento persistido na outbox aguardando publicacao no Kafka.
 *
 * <p>Nasce em {@link OutboxStatus#PENDENTE} e transita para {@link OutboxStatus#PUBLICADO} quando o
 * Kafka confirma o envio, ou para {@link OutboxStatus#FALHA} apos esgotar as tentativas (DLQ
 * persistida). O {@code payload} carrega o evento serializado em JSON.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

  @Id private UUID eventId;

  private String aggregateType;
  private UUID aggregateId;
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  private String payload;

  @Enumerated(EnumType.STRING)
  private OutboxStatus status;

  private Instant proximaTentativaEm;
  private Instant criadoEm;
  private Instant publicadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected OutboxEvent() {}

  private OutboxEvent(
      UUID eventId,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String payload,
      Instant criadoEm) {
    this.eventId = eventId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
    this.status = OutboxStatus.PENDENTE;
    this.criadoEm = criadoEm;
    this.proximaTentativaEm = criadoEm;
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
    return new OutboxEvent(eventId, aggregateType, aggregateId, eventType, payload, Instant.now());
  }

  /**
   * Marca o evento como publicado apos confirmacao do Kafka.
   *
   * @param publicadoEm instante da confirmacao
   */
  public void marcarPublicado(Instant publicadoEm) {
    this.status = OutboxStatus.PUBLICADO;
    this.publicadoEm = publicadoEm;
  }

  /**
   * Retorna a situacao atual do evento.
   *
   * @return situacao do evento
   */
  public OutboxStatus getStatus() {
    return status;
  }

  /**
   * Retorna o instante em que o evento foi publicado.
   *
   * @return instante da publicacao
   */
  public Instant getPublicadoEm() {
    return publicadoEm;
  }
}
