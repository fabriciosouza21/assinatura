package com.globo.pagamento.cobranca;

/** Status do ciclo de vida de uma cobranca no gateway de pagamento. */
public enum StatusCobranca {
  /** Cobranca criada, aguardando a confirmacao do pagamento. */
  PENDING,
  /** Pagamento aprovado. */
  APPROVED,
  /** Pagamento recusado. */
  REJECTED
}
