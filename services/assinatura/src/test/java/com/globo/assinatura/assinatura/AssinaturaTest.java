package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do agregado {@link Assinatura}.
 *
 * <p>Verifica o estado inicial de uma assinatura recem-criada, antes da persistencia.
 */
class AssinaturaTest {

  @Test
  @DisplayName("Deve nascer aguardando pagamento com uuid e datas nulas")
  void deveNascerAguardandoPagamento() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);

    assertThat(assinatura.getStatus())
        .as("Status inicial")
        .isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    assertThat(assinatura.getUuid()).as("Uuid publico atribuido").isNotNull();
    assertThatCode(() -> UUID.fromString(assinatura.getUuid()))
        .as("Uuid no formato canonico")
        .doesNotThrowAnyException();
    assertThat(assinatura.getUsuarioId()).as("Usuario interno").isEqualTo(42L);
    assertThat(assinatura.getPlano()).as("Plano").isEqualTo(Plano.PREMIUM);
    assertThat(assinatura.getDataInicio()).as("Data inicio nula enquanto aguarda").isNull();
    assertThat(assinatura.getDataExpiracao()).as("Data expiracao nula enquanto aguarda").isNull();
  }
}
