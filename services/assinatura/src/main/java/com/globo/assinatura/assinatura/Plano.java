package com.globo.assinatura.assinatura;

import java.math.BigDecimal;

/**
 * Plano da assinatura e seu valor mensal em reais.
 *
 * <p>Cada constante carrega o valor mensal como {@link BigDecimal} para evitar arredondamento.
 */
public enum Plano {
  /** Plano basico, valor mensal de R$ 19,90. */
  BASICO(new BigDecimal("19.90")),
  /** Plano premium, valor mensal de R$ 39,90. */
  PREMIUM(new BigDecimal("39.90")),
  /** Plano familia, valor mensal de R$ 59,90. */
  FAMILIA(new BigDecimal("59.90"));

  private final BigDecimal valor;

  /**
   * Constroi o plano com o valor mensal informado.
   *
   * @param valor valor mensal do plano em reais
   */
  Plano(BigDecimal valor) {
    this.valor = valor;
  }

  /**
   * Retorna o valor mensal do plano em reais.
   *
   * @return valor mensal do plano
   */
  public BigDecimal valor() {
    return valor;
  }
}
