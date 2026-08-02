package com.globo.assinatura.shared.contrato;

/** Status do ciclo de vida da assinatura no contrato de eventos de cancelamento. */
public enum StatusAssinatura {
  /** Assinatura que aguarda o processamento do pagamento inicial. */
  AGUARDANDO_PAGAMENTO,
  /** Assinatura com acesso ativo. */
  ATIVA,
  /** Assinatura cujo pagamento foi recusado. */
  PAGAMENTO_RECUSADO,
  /** Assinatura cujo ciclo vencido aguarda o resultado da renovacao. */
  EM_RENOVACAO,
  /** Assinatura sem acesso apos o esgotamento das tentativas de renovacao. */
  SUSPENSA,
  /** Assinatura cancelada. */
  CANCELADA
}
