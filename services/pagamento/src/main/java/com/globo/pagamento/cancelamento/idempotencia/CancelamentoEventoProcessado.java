package com.globo.pagamento.cancelamento.idempotencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Registra um evento de cancelamento processado para evitar novo processamento do mesmo evento. */
@Entity
@Table(name = "cancelamento_evento_processado")
public class CancelamentoEventoProcessado {

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
  protected CancelamentoEventoProcessado() {}

  /**
   * Cria o registro de um evento de cancelamento processado.
   *
   * @param eventId identificador unico do evento
   * @param assinaturaId identificador publico da assinatura do evento
   */
  public CancelamentoEventoProcessado(UUID eventId, UUID assinaturaId) {
    this.eventId = eventId;
    this.assinaturaId = assinaturaId;
    this.processadoEm = Instant.now();
  }

  /**
   * Retorna o identificador tecnico do registro.
   *
   * @return identificador tecnico, ou {@code null} antes da persistencia
   */
  public Long getId() {
    return id;
  }

  /**
   * Retorna o identificador unico do evento processado.
   *
   * @return identificador do evento
   */
  public UUID getEventId() {
    return eventId;
  }

  /**
   * Retorna o identificador da assinatura associada ao evento.
   *
   * @return identificador publico da assinatura
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
