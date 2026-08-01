package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testes unitarios do agregado {@link Renovacao}. */
class RenovacaoTest {

  @Test
  @DisplayName("Deve nascer pendente ao criar a renovacao")
  void deveNascerPendenteAoCriar() {
    Renovacao renovacao = new Renovacao(1L, LocalDate.of(2026, 2, 1), 1);

    assertThat(renovacao.getStatus())
        .as("Status inicial da renovacao")
        .isEqualTo(StatusRenovacao.PENDENTE);
  }

  @Test
  @DisplayName("Deve transitar para aprovada ao aprovar a renovacao")
  void deveTransitarParaAprovadaAoAprovar() {
    Renovacao renovacao = new Renovacao(1L, LocalDate.of(2026, 2, 1), 1);

    renovacao.aprovar();

    assertThat(renovacao.getStatus())
        .as("Status transita para aprovada")
        .isEqualTo(StatusRenovacao.APROVADA);
  }

  @Test
  @DisplayName("Deve transitar para tentativas esgotadas ao esgotar a renovacao")
  void deveTransitarParaTentativasEsgotadasAoEsgotar() {
    Renovacao renovacao = new Renovacao(1L, LocalDate.of(2026, 2, 1), 1);

    renovacao.esgotarTentativas();

    assertThat(renovacao.getStatus())
        .as("Status transita para tentativas esgotadas")
        .isEqualTo(StatusRenovacao.TENTATIVAS_ESGOTADA);
  }

  @Test
  @DisplayName("Deve lancar excecao ao aprovar renovacao ja aprovada")
  void deveLancarExcecaoAoAprovarRenovacaoJaAprovada() {
    Renovacao renovacao = new Renovacao(1L, LocalDate.of(2026, 2, 1), 1);
    renovacao.aprovar();

    assertThatThrownBy(renovacao::aprovar)
        .as("Aprovar renovacao ja aprovada e transicao invalida")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Deve lancar excecao ao esgotar tentativas de renovacao ja esgotada")
  void deveLancarExcecaoAoEsgotarRenovacaoJaEsgotada() {
    Renovacao renovacao = new Renovacao(1L, LocalDate.of(2026, 2, 1), 1);
    renovacao.esgotarTentativas();

    assertThatThrownBy(renovacao::esgotarTentativas)
        .as("Esgotar tentativas de renovacao ja esgotada e transicao invalida")
        .isInstanceOf(IllegalStateException.class);
  }
}
