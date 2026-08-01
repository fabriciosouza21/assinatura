package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Teste unitario puro de {@link TentativaCobranca}, sem contexto Spring. */
class TentativaCobrancaTest {

  @Test
  @DisplayName("Deve registrar o payment id informado")
  void deveRegistrarPaymentId() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    tentativa.registrarCobranca("pay-123");

    assertThat(tentativa.getPaymentId())
        .as("Payment id registrado apos chamada ao gateway")
        .isEqualTo("pay-123");
  }
}
