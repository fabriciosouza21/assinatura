package com.globo.pagamento.renovacao;

/**
 * Status de uma tentativa de cobranca de renovacao.
 *
 * <p>Toda tentativa nasce {@link #PENDENTE}. O webhook do gateway pode marca-la como {@link
 * #APROVADA}, {@link #RECUSADA} ou {@link #TENTATIVAS_ESGOTADA}; o scheduler pode esgota-la ao
 * atingir o teto de falhas tecnicas. {@link #CANCELADA} impede uma cobranca apos o cancelamento da
 * assinatura.
 */
public enum StatusTentativa {
  /** Tentativa aguardando cobranca ou decisao do gateway. */
  PENDENTE,
  /** Cobranca aprovada pelo gateway; encerra as tentativas da renovacao. */
  APROVADA,
  /** Cobranca recusada pelo gateway, com uma nova tentativa agendada. */
  RECUSADA,
  /** Cobranca sem tentativas restantes; esgota o ciclo da renovacao. */
  TENTATIVAS_ESGOTADA,
  /** Cobranca cancelada antes de uma decisao do gateway. */
  CANCELADA
}
