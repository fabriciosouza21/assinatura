package com.globo.assinatura.shared.outbox;

/** Ciclo de vida de um evento na outbox. */
public enum OutboxStatus {
  /** Aguardando publicacao no Kafka. */
  PENDENTE,
  /** Publicado e confirmado pelo Kafka. */
  PUBLICADO,
  /** Tentativas esgotadas; DLQ persistida para reprocessamento manual. */
  FALHA,
  /**
   * Promovido automaticamente de {@link #FALHA} apos o timeout da DLQ, para um novo ciclo de
   * tentativas de publicacao. Ao esgotar as tentativas de novo, o evento retorna a {@link #FALHA},
   * permanecendo elegivel a recuperacao ate o limite de ciclos.
   */
  RETENTATIVA_DLQ
}
