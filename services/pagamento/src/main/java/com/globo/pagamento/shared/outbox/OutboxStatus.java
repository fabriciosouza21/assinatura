package com.globo.pagamento.shared.outbox;

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
  FALHA,
  /**
   * Promovido automaticamente de {@link #FALHA} apos o timeout da DLQ, para um novo ciclo de
   * tentativas de publicacao.
   */
  RETENTATIVA_DLQ
}
