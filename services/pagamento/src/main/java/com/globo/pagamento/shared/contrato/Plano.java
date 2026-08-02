package com.globo.pagamento.shared.contrato;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.math.BigDecimal;

/** Plano serializado nos contratos de eventos Kafka. */
public enum Plano {
  /** Plano basico, no valor mensal de R$ 19,90. */
  BASICO(new BigDecimal("19.90")),

  /** Plano premium, no valor mensal de R$ 39,90. */
  PREMIUM(new BigDecimal("39.90")),

  /** Plano familia, no valor mensal de R$ 59,90. */
  FAMILIA(new BigDecimal("59.90"));

  private final BigDecimal valor;

  Plano(BigDecimal valor) {
    this.valor = valor;
  }

  /**
   * Retorna o valor mensal serializado no contrato.
   *
   * @return valor mensal do plano
   */
  public BigDecimal valor() {
    return valor;
  }

  /**
   * Desserializa o plano a partir do nome do contrato.
   *
   * @param valor nome serializado do plano
   * @return plano correspondente, ou {@code null} quando o nome não for suportado
   */
  @JsonCreator
  public static Plano from(String valor) {
    if (valor == null) {
      return null;
    }
    for (Plano plano : values()) {
      if (plano.name().equals(valor)) {
        return plano;
      }
    }
    return null;
  }
}
