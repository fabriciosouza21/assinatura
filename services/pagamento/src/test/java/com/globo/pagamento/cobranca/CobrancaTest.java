package com.globo.pagamento.cobranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste unitario da transicao de status de {@link Cobranca}.
 *
 * <p>Garante que uma cobranca ja terminal (aprovada/recusada) nao regreda para {@code PENDING} ao
 * receber uma notificacao atrasada do gateway (entrega at-least-once, sem ordem garantida), mas que
 * transicoes legitimas a partir de {@code PENDING} continuam funcionando.
 */
class CobrancaTest {

  @Test
  @DisplayName("Nao deve regredir cobranca aprovada para pendente em notificacao atrasada")
  void naoDeveRegredirCobrancaAprovadaParaPendente() {
    Cobranca cobranca = new Cobranca("assinatura-uuid", "pay_123", StatusCobranca.APPROVED);
    Instant atualizadoEmAntes = cobranca.getAtualizadoEm();

    cobranca.marcarComo(StatusCobranca.PENDING);

    assertThat(cobranca.getStatus())
        .as("Status terminal preservado apos notificacao atrasada")
        .isEqualTo(StatusCobranca.APPROVED);
    assertThat(cobranca.getAtualizadoEm())
        .as("AtualizadoEm nao alterado em transicao rejeitada")
        .isEqualTo(atualizadoEmAntes);
  }

  @Test
  @DisplayName("Deve avancar cobranca pendente para aprovada")
  void deveAvancarCobrancaPendenteParaAprovada() {
    Cobranca cobranca = new Cobranca("assinatura-uuid", "pay_123", StatusCobranca.PENDING);

    cobranca.marcarComo(StatusCobranca.APPROVED);

    assertThat(cobranca.getStatus())
        .as("Status avancado de pendente para aprovado")
        .isEqualTo(StatusCobranca.APPROVED);
  }
}
