package com.globo.assinatura.adesao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessado;
import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessadoRepository;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.shared.contrato.PagamentoStatusAtualizado;
import com.globo.assinatura.shared.contrato.StatusPagamento;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ConfirmarPagamentoAdesaoTest {

  /** Duracao padrao do ciclo de renovacao: 30 dias. */
  private static final long CICLO_DIAS_PADRAO = 30;

  /** Duracao padrao do ciclo de renovacao, em milissegundos. */
  private static final long CICLO_MS_PADRAO = Duration.ofDays(CICLO_DIAS_PADRAO).toMillis();

  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;

  private Clock relogio;
  private ConfirmarPagamentoAdesao command;

  @BeforeEach
  void setUp() {
    relogio = Clock.systemDefaultZone();
    command =
        new ConfirmarPagamentoAdesao(
            assinaturaRepository, pagamentoEventoProcessadoRepository, relogio, CICLO_MS_PADRAO);
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
  @DisplayName("Deve carimbar o instante de processado com o relogio injetado")
  void deveCarimbarInstanteProcessadoComRelogioInjetado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    relogio = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    command =
        new ConfirmarPagamentoAdesao(
            assinaturaRepository, pagamentoEventoProcessadoRepository, relogio, CICLO_MS_PADRAO);
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-01-15T09:00:00Z"),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    ArgumentCaptor<PagamentoEventoProcessado> captor =
        ArgumentCaptor.forClass(PagamentoEventoProcessado.class);
    verify(pagamentoEventoProcessadoRepository).save(captor.capture());
    assertThat(captor.getValue().getProcessadoEm())
        .as("processado_em deve vir do relogio injetado, nao do wall-clock")
        .isEqualTo(Instant.parse("2026-01-15T10:00:00Z"));
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
        new ConfirmarPagamentoAdesao(
            assinaturaRepository, pagamentoEventoProcessadoRepository, relogio, CICLO_MS_PADRAO);

    command.executar(evento);

    assertThat(assinatura.getDataInicio())
        .as("data inicio deve ser a data fixa do relogio")
        .isEqualTo(LocalDate.of(2026, 1, 15));
  }

  @Test
  @DisplayName("Deve definir a data de expiracao para um ciclo apos o inicio")
  void deveDefinirDataExpiracaoParaUmCicloAposInicio() {
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
        new ConfirmarPagamentoAdesao(
            assinaturaRepository, pagamentoEventoProcessadoRepository, relogio, CICLO_MS_PADRAO);

    command.executar(evento);

    assertThat(assinatura.getDataExpiracao())
        .as("data expiracao deve avancar um ciclo (30 dias) apos o inicio")
        .isEqualTo(LocalDate.of(2026, 1, 15).plusDays(CICLO_DIAS_PADRAO));
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
    UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            eventId,
            Instant.now(),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    verify(pagamentoEventoProcessadoRepository, never()).save(any(PagamentoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve ignorar redelivery sem buscar a assinatura sob lock")
  void deveIgnorarRedeliverySemBuscarAssinaturaSobLock() {
    UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            eventId,
            Instant.now(),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    verify(assinaturaRepository, never()).buscarPorUuidParaAtualizacao(any(String.class));
  }

  @Test
  @DisplayName(
      "Deve ignorar evento para assinatura inexistente sem registrar idempotencia para redelivery")
  void deveIgnorarEventoParaAssinaturaInexistenteSemRegistrarIdempotencia() {
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
    verify(pagamentoEventoProcessadoRepository, never()).save(any(PagamentoEventoProcessado.class));
  }

  @Test
  @DisplayName(
      "Deve tratar como no-op idempotente quando save do evento processado viola indice unico"
          + " (race de rebalance)")
  void deveTratarComoNoOpQuandoSaveDoEventoProcessadoViolaIndiceUnico() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(pagamentoEventoProcessadoRepository.save(any(PagamentoEventoProcessado.class)))
        .thenThrow(new DataIntegrityViolationException("uq_pagamento_evento_processado_event_id"));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    assertThatCode(() -> command.executar(evento))
        .as(
            "violacao do indice unico no save concorrente deve ser absorvida como no-op"
                + " idempotente, sem propagar para o consumer Kafka")
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
