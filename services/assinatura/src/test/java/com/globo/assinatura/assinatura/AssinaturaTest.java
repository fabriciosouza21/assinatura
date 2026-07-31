package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
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

  @Test
  @DisplayName("Deve transitar para ativa ao receber o pagamento aprovado")
  void deveTransitarParaAtivaAoReceberPagamentoAprovado() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar((LocalDate) null, (LocalDate) null);
    assertThat(assinatura.getStatus()).as("Status apos ativacao").isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve atribuir a data de inicio ao ativar a assinatura")
  void deveAtribuirDataInicioAoAtivar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assertThat(assinatura.getDataInicio())
        .as("Data inicio atribuida na ativacao")
        .isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test
  @DisplayName("Deve atribuir a data de expiracao ao ativar a assinatura")
  void deveAtribuirDataExpiracaoAoAtivar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assertThat(assinatura.getDataExpiracao())
        .as("Data expiracao atribuida na ativacao")
        .isEqualTo(LocalDate.of(2026, 2, 1));
  }

  @Test
  @DisplayName("Deve transitar para pagamento recusado ao recusar o pagamento")
  void deveTransitarParaPagamentoRecusadoAoRecusarPagamento() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.recusarPagamento();
    assertThat(assinatura.getStatus())
        .as("Status apos recusa")
        .isEqualTo(StatusAssinatura.PAGAMENTO_RECUSADO);
  }

  @Test
  @DisplayName("Deve permanecer ativa ao receber nova ativacao")
  void devePermanecerAtivaAoReceberNovaAtivacao() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assinatura.ativar(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

    assertThat(assinatura.getDataInicio())
        .as("Data de inicio da primeira ativacao preservada")
        .isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(assinatura.getDataExpiracao())
        .as("Data de expiracao da primeira ativacao preservada")
        .isEqualTo(LocalDate.of(2026, 2, 1));
  }
}
