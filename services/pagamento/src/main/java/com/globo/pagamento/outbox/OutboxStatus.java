package com.globo.pagamento.outbox;

/**
 * Situacao de um evento na outbox.
 *
 * <p>{@link #PENDENTE} aguarda publicacao no Kafka; os demais estados surgem com as transicoes
 * registradas no {@link OutboxEvent}.
 */
public enum OutboxStatus {
  PENDENTE,
  /** Publicado e confirmado pelo Kafka. */
  PUBLICADO,
  /** Falha definitiva apos esgotar as tentativas, aguardando reprocessamento manual. */
  FALHA
}
