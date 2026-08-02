package com.globo.assinatura.renovacao.idempotencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra o resultado de uma renovacao ja processado para fins de idempotencia.
 *
 * <p>Cada evento identificado por {@code event_id} so pode ser processado uma unica vez; a
 * constraint unica sobre {@code event_id} impede o reprocessamento de eventos duplicados entregues
 * pelo barramento de mensagens.
 */
@Entity
@Table(name = "renovacao_evento_processado")
public class RenovacaoEventoProcessado {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "renovacao_uuid", nullable = false)
  private String renovacaoUuid;

  @Column(name = "processado_em", nullable = false)
  private Instant processadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected RenovacaoEventoProcessado() {}

  /**
   * Registra o processamento do resultado de uma renovacao.
   *
   * @param eventId identificador unico do evento recebido do barramento de mensagens
   * @param renovacaoUuid uuid publico da renovacao resolvida pelo evento
   * @param processadoEm instante em que o evento foi processado, fornecido pelo relogio injetado
   */
  public RenovacaoEventoProcessado(UUID eventId, String renovacaoUuid, Instant processadoEm) {
    this.eventId = eventId;
    this.renovacaoUuid = renovacaoUuid;
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
   * Retorna o uuid publico da renovacao resolvida.
   *
   * @return uuid publico da renovacao
   */
  public String getRenovacaoUuid() {
    return renovacaoUuid;
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
