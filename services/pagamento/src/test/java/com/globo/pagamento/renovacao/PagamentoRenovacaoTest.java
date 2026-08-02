package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste unitario puro de {@link PagamentoRenovacao}, sem contexto Spring.
 *
 * <p>Concentra-se na geracao da sequencia de tentativas dentro do agregado.
 */
class PagamentoRenovacaoTest {

  private static final Instant DAQUI_A_UM_DIA = Instant.parse("2026-08-02T12:00:00Z");

  @Test
  @DisplayName("Deve registrar a primeira tentativa pendente e sem agendamento")
  void deveRegistrarPrimeiraTentativa() {
    TentativaCobranca primeira = pagamento().registrarTentativa();

    assertThat(primeira.getNumero()).as("Numero da primeira tentativa").isEqualTo(1);
    assertThat(primeira.getStatus())
        .as("Primeira tentativa nasce pendente")
        .isEqualTo(StatusTentativa.PENDENTE);
    assertThat(primeira.getProximaTentativaEm())
        .as("Primeira tentativa e cobrada de imediato")
        .isNull();
  }

  @Test
  @DisplayName("Deve registrar a tentativa seguinte com o proximo numero e o agendamento informado")
  void deveRegistrarTentativaSeguinte() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca primeira = pagamento.registrarTentativa();

    TentativaCobranca segunda = pagamento.registrarTentativa(primeira, DAQUI_A_UM_DIA);

    assertThat(segunda.getNumero()).as("Sequencia gerada pelo agregado").isEqualTo(2);
    assertThat(segunda.getStatus())
        .as("Tentativa de retry nasce pendente")
        .isEqualTo(StatusTentativa.PENDENTE);
    assertThat(segunda.getPaymentId()).as("Tentativa de retry ainda nao foi cobrada").isNull();
    assertThat(segunda.getProximaTentativaEm())
        .as("Agendamento do retry")
        .isEqualTo(DAQUI_A_UM_DIA);
  }

  @Test
  @DisplayName("Deve recusar a tentativa seguinte quando a anterior e de outra renovacao")
  void deveRecusarTentativaDeOutraRenovacao() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca deOutraRenovacao = new TentativaCobranca("outra-renovacao", 1);

    assertThatThrownBy(() -> pagamento.registrarTentativa(deOutraRenovacao, DAQUI_A_UM_DIA))
        .as("Sequencia de tentativas nao cruza renovacoes")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PagamentoRenovacao pagamento() {
    return new PagamentoRenovacao(
        "renov-uuid", "assinatura-uuid", Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
