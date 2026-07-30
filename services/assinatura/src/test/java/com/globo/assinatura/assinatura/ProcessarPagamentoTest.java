package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
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
    when(assinaturaRepository.findByUuidForUpdate(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    verify(assinaturaRepository).findByUuidForUpdate(assinatura.getUuid());
    assertThat(assinatura.getStatus())
        .as("assinatura aprovada deve transitar para ativa")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve registrar o evento processado ao aprovar o pagamento")
  void deveRegistrarEventoProcessadoQuandoPagamentoAprovado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.findByUuidForUpdate(assinatura.getUuid()))
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
    when(assinaturaRepository.findByUuidForUpdate(assinatura.getUuid()))
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
    when(assinaturaRepository.findByUuidForUpdate(assinatura.getUuid()))
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
}
