package com.globo.pagamento.webhook.idempotencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra um evento de webhook ja processado para fins de idempotencia.
 *
 * <p>O gateway entrega o webhook de forma at-least-once. Cada evento identificado por {@code
 * event_id} (= {@code X-Mock-Event-Id}) so pode disparar uma publicacao; a constraint unica sobre
 * {@code event_id} impede a republicacao de notificacoes duplicadas. O registro so e gravado apos a
 * publicacao confirmada, nunca antes.
 */
@Entity
@Table(name = "webhook_evento_processado")
public class WebhookEventoProcessado {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "assinatura_id", nullable = false)
  private UUID assinaturaId;

  @Column(name = "processado_em", nullable = false)
  private Instant processadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected WebhookEventoProcessado() {}

  /**
   * Registra o processamento confirmado de um evento de webhook.
   *
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook)
   * @param assinaturaId uuid publico da assinatura alvo do evento (= {@code externalReference})
   */
  public WebhookEventoProcessado(UUID eventId, UUID assinaturaId) {
    this.eventId = eventId;
    this.assinaturaId = assinaturaId;
    this.processadoEm = Instant.now();
  }

  /**
   * Retorna o identificador unico do evento.
   *
   * @return event id recebido do webhook
   */
  public UUID getEventId() {
    return eventId;
  }

  /**
   * Retorna o uuid publico da assinatura alvo do evento.
   *
   * @return uuid publico da assinatura
   */
  public UUID getAssinaturaId() {
    return assinaturaId;
  }

  /**
   * Retorna o instante em que o evento foi processado.
   *
   * @return instante de processamento
   */
  public Instant getProcessadoEm() {
    return processadoEm;
  }
}
