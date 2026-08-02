package com.globo.pagamento.outbox;

/**
 * Situacao de um evento na outbox.
 *
 * <p>{@link #PENDENTE} aguarda publicacao no Kafka; os demais estados surgem com as transicoes
 * registradas no {@link OutboxEvent}.
 */
public enum OutboxStatus {
  PENDENTE
}
