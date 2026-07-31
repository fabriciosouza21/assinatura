package com.globo.pagamento.renovacao;

/**
 * Status de uma tentativa de cobranca de renovacao.
 *
 * <p>No escopo de criacao, toda tentativa nasce {@link #PENDENTE}. Os status terminais ({@code
 * APROVADA}, {@code RECUSADA}, {@code TENTATIVAS_ESGOTADA}) sao acrescentados pelas entregas que de
 * fato cobram e decidem a tentativa (webhook e scheduler de tentativas).
 */
public enum StatusTentativa {
  PENDENTE
}
