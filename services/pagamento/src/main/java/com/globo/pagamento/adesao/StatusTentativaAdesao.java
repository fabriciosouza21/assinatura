package com.globo.pagamento.adesao;

/**
 * Status de uma tentativa de cobranca de adesao.
 *
 * <p>Toda tentativa nasce {@link #PENDENTE}. O scheduler pode marca-la como {@link #COBRADA} ao
 * criar a cobranca no gateway com sucesso, ou {@link #ESGOTADA} ao atingir o teto de falhas
 * tecnicas consecutivas do gateway.
 */
public enum StatusTentativaAdesao {
  /** Tentativa aguardando cobranca no gateway. */
  PENDENTE,
  /** Cobranca criada no gateway; aguarda decisao via webhook. */
  COBRADA,
  /** Teto de falhas tecnicas atingido; assinatura sera marcada como pagamento falhou. */
  ESGOTADA
}
