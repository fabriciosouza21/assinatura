package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  @DisplayName("Deve nascer com renovacao automatica habilitada por padrao (opt-out)")
  void deveNascerComRenovacaoAutomaticaHabilitada() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);

    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica nasce habilitada (opt-out, decisao O-R2)")
        .isTrue();
  }

  @Test
  @DisplayName("Deve estabelecer o primeiro ciclo de renovacao ao ativar")
  void deveEstabelecerPrimeiroCicloAoAtivar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);

    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assertThat(assinatura.getInicioCiclo())
        .as("Inicio do ciclo coincide com a data de inicio")
        .isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(assinatura.getFimCiclo())
        .as("Fim do ciclo coincide com a data de expiracao")
        .isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(assinatura.getProximaRenovacaoEm())
        .as("Proxima renovacao coincide com o fim do primeiro ciclo")
        .isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica habilitada no primeiro ciclo")
        .isTrue();
  }

  @Test
  @DisplayName("Deve voltar para ativa ao renovar a partir de em renovacao")
  void deveVoltarParaAtivaAoRenovar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.iniciarRenovacao();

    assinatura.renovar(LocalDate.of(2026, 3, 1));

    assertThat(assinatura.getStatus())
        .as("Status volta para ativa apos renovar")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve avancar o inicio do ciclo para o fim anterior ao renovar")
  void deveAvancarInicioDoCicloAoRenovar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assinatura.renovar(LocalDate.of(2026, 3, 1));

    assertThat(assinatura.getInicioCiclo())
        .as("Inicio do ciclo avanca para o fim anterior")
        .isEqualTo(LocalDate.of(2026, 2, 1));
  }

  @Test
  @DisplayName("Deve definir o fim do ciclo com o novo vencimento ao renovar")
  void deveDefinirFimDoCicloAoRenovar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assinatura.renovar(LocalDate.of(2026, 3, 1));

    assertThat(assinatura.getFimCiclo())
        .as("Fim do ciclo assume o novo vencimento")
        .isEqualTo(LocalDate.of(2026, 3, 1));
  }

  @Test
  @DisplayName("Deve agendar a proxima renovacao para o novo fim do ciclo")
  void deveAgendarProximaRenovacaoAoRenovar() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assinatura.renovar(LocalDate.of(2026, 3, 1));

    assertThat(assinatura.getProximaRenovacaoEm())
        .as("Proxima renovacao coincide com o novo fim do ciclo")
        .isEqualTo(LocalDate.of(2026, 3, 1));
  }

  @Test
  @DisplayName("Deve transitar para em renovacao ao iniciar a renovacao")
  void deveTransitarParaEmRenovacaoAoIniciarRenovacao() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assinatura.iniciarRenovacao();

    assertThat(assinatura.getStatus())
        .as("Status transita para em renovacao")
        .isEqualTo(StatusAssinatura.EM_RENOVACAO);
  }

  @Test
  @DisplayName("Deve transitar para suspensa ao suspender a assinatura em renovacao")
  void deveTransitarParaSuspensaAoSuspender() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.iniciarRenovacao();

    assinatura.suspender();

    assertThat(assinatura.getStatus())
        .as("Status transita para suspensa")
        .isEqualTo(StatusAssinatura.SUSPENSA);
  }

  @Test
  @DisplayName("Deve lancar excecao ao suspender assinatura ativa")
  void deveLancarExcecaoAoSuspenderAtiva() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

    assertThatThrownBy(assinatura::suspender)
        .as("Suspender assinatura ativa e transicao invalida")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Deve agendar o cancelamento ao solicitar em assinatura ativa")
  void deveAgendarCancelamentoAoSolicitarCancelamento() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura ativa")
        .isEqualTo(EfeitoCancelamento.AGENDADO);
    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica desligada ao agendar cancelamento")
        .isFalse();
    assertThat(assinatura.getStatus())
        .as("Status permanece ativa ate o fim do ciclo")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve cancelar imediatamente ao solicitar em assinatura suspensa")
  void deveCancelarImediatamenteAoSolicitarCancelamentoEmAssinaturaSuspensa() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.iniciarRenovacao();
    assinatura.suspender();

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura suspensa")
        .isEqualTo(EfeitoCancelamento.IMEDIATO);
    assertThat(assinatura.getStatus())
        .as("Status transita para cancelada imediatamente")
        .isEqualTo(StatusAssinatura.CANCELADA);
  }

  @Test
  @DisplayName("Deve cancelar imediatamente ao solicitar em assinatura aguardando pagamento")
  void deveCancelarImediatamenteAoSolicitarCancelamentoEmAssinaturaAguardandoPagamento() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura aguardando pagamento")
        .isEqualTo(EfeitoCancelamento.IMEDIATO);
    assertThat(assinatura.getStatus())
        .as("Status transita para cancelada imediatamente")
        .isEqualTo(StatusAssinatura.CANCELADA);
  }

  @Test
  @DisplayName("Deve cancelar imediatamente ao solicitar em assinatura com pagamento recusado")
  void deveCancelarImediatamenteAoSolicitarCancelamentoEmAssinaturaComPagamentoRecusado() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.recusarPagamento();

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura com pagamento recusado")
        .isEqualTo(EfeitoCancelamento.IMEDIATO);
    assertThat(assinatura.getStatus())
        .as("Status transita para cancelada imediatamente")
        .isEqualTo(StatusAssinatura.CANCELADA);
  }

  @Test
  @DisplayName("Deve agendar cancelamento ao solicitar em assinatura em renovacao")
  void deveAgendarCancelamentoAoSolicitarCancelamentoEmAssinaturaEmRenovacao() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.iniciarRenovacao();

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura em renovacao")
        .isEqualTo(EfeitoCancelamento.AGENDADO);
    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica desligada ao agendar cancelamento")
        .isFalse();
    assertThat(assinatura.getStatus())
        .as("Status permanece em renovacao ate a conclusao da cobranca")
        .isEqualTo(StatusAssinatura.EM_RENOVACAO);
  }

  @Test
  @DisplayName("Deve manter cancelada ao solicitar cancelamento novamente")
  void deveManterCanceladaAoSolicitarCancelamentoNovamente() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.solicitarCancelamento();

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito da segunda solicitacao de cancelamento")
        .isEqualTo(EfeitoCancelamento.IDEMPOTENTE);
    assertThat(assinatura.getStatus())
        .as("Status permanece cancelada apos nova solicitacao")
        .isEqualTo(StatusAssinatura.CANCELADA);
  }

  @Test
  @DisplayName("Deve manter cancelamento agendado ao solicitar novamente em assinatura ativa")
  void deveManterCancelamentoAgendadoAoSolicitarNovamenteEmAssinaturaAtiva() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.solicitarCancelamento();

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito da segunda solicitacao de cancelamento agendado")
        .isEqualTo(EfeitoCancelamento.IDEMPOTENTE);
    assertThat(assinatura.getStatus())
        .as("Status permanece ativa apos nova solicitacao")
        .isEqualTo(StatusAssinatura.ATIVA);
    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica permanece desligada")
        .isFalse();
  }

  @Test
  @DisplayName("Deve desabilitar renovacao automatica no cancelamento imediato")
  void deveDesabilitarRenovacaoAutomaticaAoCancelarImediatamenteAssinaturaAguardandoPagamento() {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();

    assertThat(efeito)
        .as("Efeito do cancelamento em assinatura aguardando pagamento")
        .isEqualTo(EfeitoCancelamento.IMEDIATO);
    assertThat(assinatura.isRenovacaoAutomatica())
        .as("Renovacao automatica desligada no cancelamento imediato")
        .isFalse();
    assertThat(assinatura.getStatus())
        .as("Status transita para cancelada imediatamente")
        .isEqualTo(StatusAssinatura.CANCELADA);
  }
}
