package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import com.globo.assinatura.pagamento.PagamentoEventoProcessado;
import com.globo.assinatura.pagamento.PagamentoEventoProcessadoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessarPagamentoTest {

  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;

  private Clock relogio;
  private ProcessarPagamento command;

  @BeforeEach
  void setUp() {
    relogio = Clock.systemDefaultZone();
    command =
        new ProcessarPagamento(assinaturaRepository, pagamentoEventoProcessadoRepository, relogio);
  }

  @Test
  @DisplayName(
      "Deve carregar a assinatura sob lock e transita-la para ativa quando o pagamento e aprovado")
  void deveAtivarAssinaturaQuandoPagamentoAprovado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    verify(assinaturaRepository).buscarPorUuidParaAtualizacao(assinatura.getUuid());
    assertThat(assinatura.getStatus())
        .as("assinatura aprovada deve transitar para ativa")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve registrar o evento processado ao aprovar o pagamento")
  void deveRegistrarEventoProcessadoQuandoPagamentoAprovado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    ArgumentCaptor<PagamentoEventoProcessado> captor =
        ArgumentCaptor.forClass(PagamentoEventoProcessado.class);
    verify(pagamentoEventoProcessadoRepository).save(captor.capture());
    assertThat(captor.getValue().getEventId())
        .as("evento processado deve registrar o eventId recebido")
        .isEqualTo(evento.eventId());
  }

  @Test
  @DisplayName("Deve definir a data de inicio como hoje ao aprovar o pagamento")
  void deveDefinirDataInicioComoHojeAoAprovarPagamento() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());
    relogio = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    command =
        new ProcessarPagamento(assinaturaRepository, pagamentoEventoProcessadoRepository, relogio);

    command.executar(evento);

    assertThat(assinatura.getDataInicio())
        .as("data inicio deve ser a data fixa do relogio")
        .isEqualTo(LocalDate.of(2026, 1, 15));
  }

  @Test
  @DisplayName("Deve definir a data de expiracao para um mes apos o inicio")
  void deveDefinirDataExpiracaoParaUmMesAposInicio() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());
    relogio = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    command =
        new ProcessarPagamento(assinaturaRepository, pagamentoEventoProcessadoRepository, relogio);

    command.executar(evento);

    assertThat(assinatura.getDataExpiracao())
        .as("data expiracao deve ser um mes apos o inicio")
        .isEqualTo(LocalDate.of(2026, 2, 15));
  }

  @Test
  @DisplayName("Deve transitar para pagamento recusado quando o pagamento e rejeitado")
  void deveTransitarParaPagamentoRecusadoQuandoRejeitado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.REJECTED,
            UUID.randomUUID());

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("assinatura rejeitada deve ir para pagamento recusado")
        .isEqualTo(StatusAssinatura.PAGAMENTO_RECUSADO);
  }

  @Test
  @DisplayName("Deve manter a assinatura inalterada quando o pagamento esta pendente")
  void deveManterAssinaturaInalteradaQuandoPagamentoPendente() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.PENDING,
            UUID.randomUUID());

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("assinatura pendente deve permanecer aguardando pagamento")
        .isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    verify(pagamentoEventoProcessadoRepository, never()).save(any(PagamentoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve ignorar redelivery de evento ja processado")
  void deveIgnorarRedeliveryDeEventoJaProcessado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            eventId,
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("redelivery nao deve transitar a assinatura")
        .isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    verify(pagamentoEventoProcessadoRepository, never()).save(any(PagamentoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve ignorar silenciosamente evento para assinatura inexistente")
  void deveIgnorarSilenciosamenteEventoParaAssinaturaInexistente() {
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(any(String.class)))
        .thenReturn(Optional.empty());
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.randomUUID(),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    assertThatCode(() -> command.executar(evento))
        .as("evento para assinatura inexistente nao deve lancar excecao")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Deve manter assinatura ativa ao receber evento tardio rejeitado")
  void deveManterAssinaturaAtivaAoReceberEventoTardioRejeitado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.REJECTED,
            UUID.randomUUID());

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("evento tardio nao deve reverter assinatura ja ativa")
        .isEqualTo(StatusAssinatura.ATIVA);
  }
}
