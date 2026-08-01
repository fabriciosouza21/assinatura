package com.globo.assinatura.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.Renovacao;
import com.globo.assinatura.assinatura.RenovacaoRepository;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.assinatura.StatusRenovacao;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.outbox.OutboxStatus;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testes unitarios do {@link RenovacaoScheduler}.
 *
 * <p>Verifica que o scheduler cria a renovacao, transita a assinatura para {@code EM_RENOVACAO} e
 * grava {@code RenovacaoSolicitada} na outbox na mesma transacao; cancela assinaturas com opt-out;
 * e e idempotente por ciclo.
 */
@ExtendWith(MockitoExtension.class)
class RenovacaoSchedulerTest {

  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private RenovacaoRepository renovacaoRepository;
  @Mock private OutboxRepository outboxRepository;

  private RenovacaoScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new RenovacaoScheduler(
            assinaturaRepository,
            renovacaoRepository,
            outboxRepository,
            JsonMapper.builder().build(),
            100);
  }

  @Test
  @DisplayName("Deve criar renovacao, marcar assinatura em renovacao e gravar evento na outbox")
  void deveCriarRenovacaoMarcarEmRenovacaoGravandoEvento() {
    Assinatura assinatura = assinaturaAtivaVencida(true);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(false);
    when(renovacaoRepository.countByAssinaturaId(anyLong())).thenReturn(0L);
    when(renovacaoRepository.save(any(Renovacao.class))).thenAnswer(inv -> inv.getArgument(0));

    scheduler.varrerVencimentos();

    ArgumentCaptor<Renovacao> renovacaoCaptor = ArgumentCaptor.forClass(Renovacao.class);
    verify(renovacaoRepository).save(renovacaoCaptor.capture());
    Renovacao renovacao = renovacaoCaptor.getValue();
    assertThat(renovacao.getAssinaturaId()).as("Renovacao vinculada a assinatura").isEqualTo(1L);
    assertThat(renovacao.getStatus())
        .as("Renovacao nasce pendente")
        .isEqualTo(StatusRenovacao.PENDENTE);
    assertThat(renovacao.getUuid()).as("Uuid publico gerado").isNotNull();
    assertThat(renovacao.getNumeroCiclo()).as("Primeiro ciclo ordinal").isEqualTo(1);

    assertThat(assinatura.getStatus())
        .as("Assinatura em renovacao aguardando cobranca")
        .isEqualTo(StatusAssinatura.EM_RENOVACAO);

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(outboxCaptor.capture());
    OutboxEvent evento = outboxCaptor.getValue();
    assertThat(evento.getEventType())
        .as("Tipo do evento da outbox")
        .isEqualTo("RenovacaoSolicitada");
    assertThat(evento.getStatus()).as("Evento nasce pendente").isEqualTo(OutboxStatus.PENDENTE);
  }

  @Test
  @DisplayName("Deve cancelar assinatura com opt-out no vencimento sem criar renovacao")
  void deveCancelarAssinaturaComOptOutNoVencimento() {
    Assinatura assinatura = assinaturaAtivaVencida(false);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));

    scheduler.varrerVencimentos();

    assertThat(assinatura.getStatus())
        .as("Assinatura cancelada por opt-out")
        .isEqualTo(StatusAssinatura.CANCELADA);
    verify(renovacaoRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Nao deve duplicar renovacao ja existente para o ciclo")
  void naoDeveDuplicarRenovacaoJaExistente() {
    Assinatura assinatura = assinaturaAtivaVencida(true);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(true);

    scheduler.varrerVencimentos();

    verify(renovacaoRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve incrementar o ordinal do ciclo a partir das renovacoes anteriores")
  void deveIncrementarOrdinalDoCiclo() {
    Assinatura assinatura = assinaturaAtivaVencida(true);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(false);
    when(renovacaoRepository.countByAssinaturaId(anyLong())).thenReturn(2L);
    when(renovacaoRepository.save(any(Renovacao.class))).thenAnswer(inv -> inv.getArgument(0));

    scheduler.varrerVencimentos();

    ArgumentCaptor<Renovacao> renovacaoCaptor = ArgumentCaptor.forClass(Renovacao.class);
    verify(renovacaoRepository).save(renovacaoCaptor.capture());
    assertThat(renovacaoCaptor.getValue().getNumeroCiclo())
        .as("Terceiro ciclo ordinal apos duas renovacoes")
        .isEqualTo(3);
  }

  private Assinatura assinaturaAtivaVencida(boolean renovacaoAutomatica) {
    Assinatura assinatura = new Assinatura(1L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    // O id tecnico e gerado pelo banco em producao; no teste unitario e ajustado por reflection
    // para que as chamadas aos repositorios recebam o identificador esperado.
    setId(assinatura, 1L);
    if (!renovacaoAutomatica) {
      // opt-out: nao ha endpoint publico de opt-out (roadmap futuro), entao o flag e ajustado
      // direto no agregado para exercitar o ramo de cancelamento do scheduler.
      desabilitarRenovacaoAutomatica(assinatura);
    }
    return assinatura;
  }

  private static void setId(Assinatura assinatura, Long id) {
    try {
      Field campo = Assinatura.class.getDeclaredField("id");
      campo.setAccessible(true);
      campo.set(assinatura, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Falha ao ajustar id no teste", e);
    }
  }

  private static void desabilitarRenovacaoAutomatica(Assinatura assinatura) {
    try {
      Field campo = Assinatura.class.getDeclaredField("renovacaoAutomatica");
      campo.setAccessible(true);
      campo.setBoolean(assinatura, false);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Falha ao ajustar renovacaoAutomatica no teste", e);
    }
  }
}
