package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do enum {@link Plano}.
 *
 * <p>Verifica o mapeamento de cada plano ao seu valor mensal em reais, representado como {@link
 * BigDecimal} para evitar arredondamento.
 */
class PlanoTest {

  @Test
  @DisplayName("Deve mapear cada plano ao seu valor mensal em reais")
  void deveMapearPlanoAoValor() {
    assertThat(Plano.BASICO.valor())
        .as("Valor do plano BASICO")
        .isEqualByComparingTo(new BigDecimal("19.90"));
    assertThat(Plano.PREMIUM.valor())
        .as("Valor do plano PREMIUM")
        .isEqualByComparingTo(new BigDecimal("39.90"));
    assertThat(Plano.FAMILIA.valor())
        .as("Valor do plano FAMILIA")
        .isEqualByComparingTo(new BigDecimal("59.90"));
  }
}
