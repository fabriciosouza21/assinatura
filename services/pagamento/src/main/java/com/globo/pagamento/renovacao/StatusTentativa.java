package com.globo.pagamento.renovacao;

/**
 * Status de uma tentativa de cobranca de renovacao.
 *
 * <p>Toda tentativa nasce {@link #PENDENTE} e e decidida pelo webhook do gateway: {@link #APROVADA}
 * encerra o ciclo com sucesso, {@link #RECUSADA} da lugar a uma nova tentativa agendada e {@link
 * #TENTATIVAS_ESGOTADA} marca a recusa que esgota o ciclo.
 */
public enum StatusTentativa {
  /** Tentativa aguardando cobranca ou decisao do gateway. */
  PENDENTE,
  /** Cobranca aprovada pelo gateway; encerra as tentativas da renovacao. */
  APROVADA,
  /** Cobranca recusada pelo gateway, com uma nova tentativa agendada. */
  RECUSADA,
  /** Cobranca recusada sem tentativas restantes; esgota o ciclo da renovacao. */
  TENTATIVAS_ESGOTADA
}
