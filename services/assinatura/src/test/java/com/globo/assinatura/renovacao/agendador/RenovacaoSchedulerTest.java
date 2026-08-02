package com.globo.assinatura.renovacao.agendador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.renovacao.Renovacao;
import com.globo.assinatura.renovacao.RenovacaoRepository;
import com.globo.assinatura.renovacao.StatusRenovacao;
import com.globo.assinatura.shared.cache.CacheVersionado;
import com.globo.assinatura.shared.contrato.AssinaturaCancelada;
import com.globo.assinatura.shared.contrato.RenovacaoSolicitada;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import com.globo.assinatura.shared.outbox.OutboxStatus;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
  @Mock private UsuarioRepository usuarioRepository;
  @Mock private CacheVersionado cacheVersionado;

  private RenovacaoScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new RenovacaoScheduler(
            assinaturaRepository,
            renovacaoRepository,
            outboxRepository,
            JsonMapper.builder().build(),
            usuarioRepository,
            cacheVersionado,
            100,
            Clock.systemUTC());
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

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(outboxCaptor.capture());
    OutboxEvent outboxEvent = outboxCaptor.getValue();
    assertThat(outboxEvent.getEventType())
        .as("Tipo do evento da outbox")
        .isEqualTo("AssinaturaCancelada");
    assertThat(outboxEvent.getStatus())
        .as("Evento nasce pendente")
        .isEqualTo(OutboxStatus.PENDENTE);

    AssinaturaCancelada evento =
        jsonMapper().readValue(outboxEvent.getPayload(), AssinaturaCancelada.class);
    assertThat(evento.status())
        .as("Status cancelado no evento")
        .isEqualTo(com.globo.assinatura.shared.contrato.StatusAssinatura.CANCELADA);
    assertThat(evento.fimCiclo())
        .as("Fim do ciclo encerrado no evento")
        .isEqualTo(LocalDate.of(2026, 2, 1));
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

  @Test
  @DisplayName("Deve buscar as vencidas usando a data do relogio injetado, nao o relogio de parede")
  void deveUsarDataDoRelogioInjetadoAoBuscarVencidas() {
    RenovacaoScheduler schedulerComRelogio = schedulerComRelogioFixo("2026-01-15T10:00:00Z");
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt())).thenReturn(List.of());

    schedulerComRelogio.varrerVencimentos();

    ArgumentCaptor<LocalDate> dataCaptor = ArgumentCaptor.forClass(LocalDate.class);
    verify(assinaturaRepository).buscarVencidasParaRenovacao(dataCaptor.capture(), eq(100));
    assertThat(dataCaptor.getValue())
        .as("Data do relogio injetado repassada a busca de vencidas")
        .isEqualTo(LocalDate.of(2026, 1, 15));
  }

  @Test
  @DisplayName("Deve carimbar o instante do evento com o relogio injetado")
  void deveCarimbarInstanteDoEventoComRelogioInjetado() {
    Instant instanteFixo = Instant.parse("2026-01-15T10:00:00Z");
    Assinatura assinatura = assinaturaAtivaVencida(true);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(false);
    when(renovacaoRepository.countByAssinaturaId(anyLong())).thenReturn(0L);
    when(renovacaoRepository.save(any(Renovacao.class))).thenAnswer(inv -> inv.getArgument(0));
    RenovacaoScheduler schedulerComRelogio = schedulerComRelogioFixo(instanteFixo.toString());

    schedulerComRelogio.varrerVencimentos();

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(outboxCaptor.capture());
    RenovacaoSolicitada evento =
        jsonMapper().readValue(outboxCaptor.getValue().getPayload(), RenovacaoSolicitada.class);
    assertThat(evento.ocorridoEm())
        .as("Instante do evento carimbado pelo relogio injetado")
        .isEqualTo(instanteFixo);
  }

  private RenovacaoScheduler schedulerComRelogioFixo(String instanteIso) {
    Clock relogioFixo = Clock.fixed(Instant.parse(instanteIso), ZoneOffset.UTC);
    return new RenovacaoScheduler(
        assinaturaRepository,
        renovacaoRepository,
        outboxRepository,
        JsonMapper.builder().build(),
        usuarioRepository,
        cacheVersionado,
        100,
        relogioFixo);
  }

  private static JsonMapper jsonMapper() {
    return JsonMapper.builder().build();
  }

  @Test
  @DisplayName("Deve processar a segunda assinatura do lote mesmo quando a primeira falha")
  void deveProcessarDemaisAssinaturasMesmoQuandoUmaFalhar() {
    Assinatura primeira = assinaturaAtivaVencida(true);
    Assinatura segunda = assinaturaAtivaVencida(true);
    setId(segunda, 2L);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(primeira, segunda));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(false);
    when(renovacaoRepository.countByAssinaturaId(anyLong())).thenReturn(0L);
    when(renovacaoRepository.save(any(Renovacao.class)))
        .thenThrow(new RuntimeException("falha na primeira assinatura"))
        .thenAnswer(inv -> inv.getArgument(0));

    assertThatCode(() -> scheduler.varrerVencimentos()).doesNotThrowAnyException();

    verify(outboxRepository, times(1)).save(any());
  }

  private Assinatura assinaturaAtivaVencida(boolean renovacaoAutomatica) {
    Assinatura assinatura = new Assinatura(1L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    // O id tecnico e gerado pelo banco em producao; no teste unitario e ajustado por reflection
    // para que as chamadas aos repositorios recebam o identificador esperado.
    setId(assinatura, 1L);
    if (!renovacaoAutomatica) {
      // Sem setter publico de opt-out no agregado, o flag e ajustado por reflection para
      // exercitar o ramo de cancelamento do scheduler.
      desabilitarRenovacaoAutomatica(assinatura);
    }
    return assinatura;
  }

  @Test
  @DisplayName("Deve invalidar o cache do dono ao criar renovacao na varredura")
  void deveInvalidarCacheDoDonoAoCriarRenovacao() {
    Assinatura assinatura = assinaturaAtivaVencida(true);
    Usuario dono = donoDaAssinatura();
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(false);
    when(renovacaoRepository.countByAssinaturaId(anyLong())).thenReturn(0L);
    when(renovacaoRepository.save(any(Renovacao.class))).thenAnswer(inv -> inv.getArgument(0));
    when(usuarioRepository.findById(1L)).thenReturn(Optional.of(dono));

    scheduler.varrerVencimentos();

    verify(cacheVersionado).invalidarAposCommit("assinatura:list:versao:" + dono.getUuid());
  }

  @Test
  @DisplayName("Deve invalidar o cache do dono ao cancelar por opt-out na varredura")
  void deveInvalidarCacheDoDonoAoCancelarPorOptOut() {
    Assinatura assinatura = assinaturaAtivaVencida(false);
    Usuario dono = donoDaAssinatura();
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(usuarioRepository.findById(1L)).thenReturn(Optional.of(dono));

    scheduler.varrerVencimentos();

    verify(cacheVersionado).invalidarAposCommit("assinatura:list:versao:" + dono.getUuid());
  }

  @Test
  @DisplayName("Nao deve invalidar o cache quando a renovacao do ciclo ja foi iniciada")
  void naoDeveInvalidarCacheQuandoRenovacaoDoCicloJaIniciada() {
    Assinatura assinatura = assinaturaAtivaVencida(true);
    when(assinaturaRepository.buscarVencidasParaRenovacao(any(), anyInt()))
        .thenReturn(List.of(assinatura));
    when(renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(anyLong(), any()))
        .thenReturn(true);

    scheduler.varrerVencimentos();

    verify(cacheVersionado, never()).invalidarAposCommit(any());
  }

  private Usuario donoDaAssinatura() {
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(1L);
    return dono;
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
