package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.messaging.event.StatusPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste unitario do {@link NormalizadorStatus}.
 *
 * <p>Verifica o mapeamento dos status do gateway para o dominio normalizado: {@code APPROVED} e
 * {@code PENDING} seguem com o mesmo nome; {@code REJECTED}, {@code CANCELLED} e {@code EXPIRED}
 * viram {@code REJECTED}.
 */
class NormalizadorStatusTest {

  private final NormalizadorStatus normalizador = new NormalizadorStatus();

  @Test
  @DisplayName("Deve manter o nome para APPROVED e PENDING")
  void devePreservarNomeParaAprovado() {
    assertThat(normalizador.normalizar(StatusGateway.APPROVED))
        .as("APPROVED mantem o nome")
        .isEqualTo(StatusPagamento.APPROVED);
    assertThat(normalizador.normalizar(StatusGateway.PENDING))
        .as("PENDING mantem o nome")
        .isEqualTo(StatusPagamento.PENDING);
  }

  @Test
  @DisplayName("Deve mapear CANCELLED e EXPIRED para REJECTED")
  void deveRejeitarCanceladoEexpirado() {
    assertThat(normalizador.normalizar(StatusGateway.REJECTED))
        .as("REJECTED mantem o nome")
        .isEqualTo(StatusPagamento.REJECTED);
    assertThat(normalizador.normalizar(StatusGateway.CANCELLED))
        .as("CANCELLED vira REJECTED")
        .isEqualTo(StatusPagamento.REJECTED);
    assertThat(normalizador.normalizar(StatusGateway.EXPIRED))
        .as("EXPIRED vira REJECTED")
        .isEqualTo(StatusPagamento.REJECTED);
  }
}
