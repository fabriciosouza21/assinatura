package com.globo.assinatura.assinatura;

/** Status do ciclo de vida de uma renovacao. */
public enum StatusRenovacao {
  /** Renovacao criada, aguardando o resultado da cobranca. */
  PENDENTE,
  /** Cobranca da renovacao aprovada. */
  APROVADA,
  /** Tentativas de cobranca da renovacao esgotadas sem aprovacao. */
  TENTATIVAS_ESGOTADA
}
