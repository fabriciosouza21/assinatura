package com.globo.assinatura.pagamento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra um evento de pagamento ja processado para fins de idempotencia.
 *
 * <p>Cada evento identificado por {@code event_id} so pode ser processado uma unica vez; a
 * constraint unica sobre {@code event_id} impede o reprocessamento de eventos duplicados entregues
 * pelo barramento de mensagens.
 */
@Entity
@Table(name = "pagamento_evento_processado")
public class PagamentoEventoProcessado {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "assinatura_uuid", nullable = false)
  private String assinaturaUuid;

  @Column(name = "processado_em", nullable = false)
  private Instant processadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected PagamentoEventoProcessado() {}

  /**
   * Registra o processamento de um evento de pagamento.
   *
   * @param eventId identificador unico do evento recebido do barramento de mensagens
   * @param assinaturaUuid uuid publico da assinatura alvo do evento
   * @param processadoEm instante em que o evento foi processado, fornecido pelo relogio injetado
   */
  public PagamentoEventoProcessado(UUID eventId, String assinaturaUuid, Instant processadoEm) {
    this.eventId = eventId;
    this.assinaturaUuid = assinaturaUuid;
    this.processadoEm = processadoEm;
  }

  /**
   * Retorna o identificador unico do evento.
   *
   * @return event id recebido do barramento de mensagens
   */
  public UUID getEventId() {
    return eventId;
  }

  /**
   * Retorna o uuid publico da assinatura alvo do evento.
   *
   * @return uuid publico da assinatura
   */
  public String getAssinaturaUuid() {
    return assinaturaUuid;
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
