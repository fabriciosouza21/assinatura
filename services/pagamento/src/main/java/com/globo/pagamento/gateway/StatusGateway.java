package com.globo.pagamento.gateway;

/**
 * Status de uma cobranca como reportado pelo gateway de pagamento.
 *
 * <p>Os valores {@code CANCELLED} e {@code EXPIRED} sao normalizados para {@code REJECTED} antes da
 * publicacao do evento.
 */
public enum StatusGateway {
  /** Cobranca criada, aguardando a confirmacao do pagamento. */
  PENDING,
  /** Pagamento aprovado. */
  APPROVED,
  /** Pagamento recusado. */
  REJECTED,
  /** Cobranca cancelada. */
  CANCELLED,
  /** Cobranca expirada sem confirmacao. */
  EXPIRED
}
