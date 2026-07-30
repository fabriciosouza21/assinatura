package com.globo.assinatura.assinatura;

/** Status do ciclo de vida de uma assinatura. */
public enum StatusAssinatura {
  /** Aguardando o processamento do pagamento. */
  AGUARDANDO_PAGAMENTO,
  /** Pagamento aprovado e assinatura ativa. */
  ATIVA,
  /** Pagamento recusado. */
  PAGAMENTO_RECUSADO
}
