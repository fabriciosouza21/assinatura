package com.globo.pagamento.messaging.event;

/** Status de um pagamento normalizado pelo Pagamento Service. */
public enum StatusPagamento {
  /** Pagamento aprovado. */
  APPROVED,
  /** Pagamento recusado (inclui CANCELLED e EXPIRED do gateway). */
  REJECTED,
  /** Pagamento em processamento. */
  PENDING
}
