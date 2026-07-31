package com.globo.assinatura.assinatura;

/** Status do ciclo de vida de uma assinatura. */
public enum StatusAssinatura {
  /** Aguardando o processamento do pagamento. */
  AGUARDANDO_PAGAMENTO,
  /** Pagamento aprovado e assinatura ativa. */
  ATIVA,
  /** Pagamento recusado. */
  PAGAMENTO_RECUSADO,
  /** Ciclo vencido e renovacao em andamento, aguardando o resultado da cobranca. */
  EM_RENOVACAO,
  /** Acesso suspenso apos o esgotamento das tentativas de cobranca da renovacao. */
  SUSPENSA
}
