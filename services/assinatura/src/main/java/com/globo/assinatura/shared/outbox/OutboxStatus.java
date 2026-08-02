package com.globo.assinatura.shared.outbox;

/** Ciclo de vida de um evento na outbox. */
public enum OutboxStatus {
  /** Aguardando publicacao no Kafka. */
  PENDENTE,
  /** Publicado e confirmado pelo Kafka. */
  PUBLICADO,
  /** Tentativas esgotadas; DLQ persistida para reprocessamento manual. */
  FALHA
}
