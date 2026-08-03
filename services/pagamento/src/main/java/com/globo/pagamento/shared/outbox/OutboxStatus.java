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
   * tentativas de publicacao. Ao esgotar as tentativas de novo, o evento retorna a {@link #FALHA},
   * permanecendo elegivel a recuperacao ate o limite de ciclos.
   */
  RETENTATIVA_DLQ
}
