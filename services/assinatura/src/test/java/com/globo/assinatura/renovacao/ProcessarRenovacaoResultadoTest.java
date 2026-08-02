package com.globo.assinatura.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.renovacao.idempotencia.RenovacaoEventoProcessado;
import com.globo.assinatura.renovacao.idempotencia.RenovacaoEventoProcessadoRepository;
import com.globo.assinatura.shared.cache.CacheVersionado;
import com.globo.assinatura.shared.contrato.AssinaturaRenovada;
import com.globo.assinatura.shared.contrato.PagamentoRenovacaoAprovado;
import com.globo.assinatura.shared.contrato.RenovacaoTentativasEsgotadas;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProcessarRenovacaoResultadoTest {

  /** Duracao padrao do ciclo de renovacao: 30 dias. */
  private static final long CICLO_DIAS_PADRAO = 30;

  /** Duracao padrao do ciclo de renovacao, em milissegundos. */
  private static final long CICLO_MS_PADRAO = Duration.ofDays(CICLO_DIAS_PADRAO).toMillis();

  @Mock private RenovacaoRepository renovacaoRepository;
  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository;
  @Mock private OutboxRepository outboxRepository;
  @Mock private UsuarioRepository usuarioRepository;
  @Mock private CacheVersionado cacheVersionado;

  private JsonMapper jsonMapper;
  private Clock relogio;
  private ProcessarRenovacaoResultado command;

  @BeforeEach
  void setUp() {
    jsonMapper = JsonMapper.builder().build();
    relogio = Clock.systemUTC();
    command =
        new ProcessarRenovacaoResultado(
            renovacaoRepository,
            assinaturaRepository,
            renovacaoEventoProcessadoRepository,
            usuarioRepository,
            cacheVersionado,
            outboxRepository,
            jsonMapper,
            relogio,
            CICLO_MS_PADRAO);
  }

  @Test
  @DisplayName("Deve voltar a assinatura para ativa ao aprovar a renovacao")
  void deveVoltarAssinaturaParaAtivaAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("assinatura deve voltar a ATIVA após renovação aprovada")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve avancar o fim do ciclo em uma duracao de ciclo ao aprovar a renovacao")
  void deveAvancarFimCicloEmUmCicloAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);
    LocalDate fimCicloAntes = assinatura.getFimCiclo();

    command.executar(evento);

    assertThat(assinatura.getFimCiclo())
        .as("novo fim de ciclo deve avancar uma duracao de ciclo sobre o anterior")
        .isEqualTo(fimCicloAntes.plusDays(CICLO_DIAS_PADRAO));
    assertThat(assinatura.getInicioCiclo())
        .as("inicio do ciclo deve assumir o fim de ciclo anterior")
        .isEqualTo(fimCicloAntes);
    assertThat(assinatura.getProximaRenovacaoEm())
        .as("proxima renovacao deve seguir o novo fim de ciclo")
        .isEqualTo(fimCicloAntes.plusDays(CICLO_DIAS_PADRAO));
  }

  @Test
  @DisplayName("Deve ignorar redelivery de evento ja processado sem buscar a renovacao sob lock")
  void deveIgnorarRedeliverySemBuscarRenovacaoSobLock() {
    UUID eventId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    when(renovacaoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            eventId,
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    verify(renovacaoRepository, never()).buscarPorUuidParaAtualizacao(any(String.class));
  }

  @Test
  @DisplayName("Deve ignorar evento para renovacao inexistente sem registrar idempotencia")
  void deveIgnorarEventoParaRenovacaoInexistenteSemRegistrarIdempotencia() {
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(any(String.class)))
        .thenReturn(Optional.empty());
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.randomUUID(),
            "pay_123",
            2);

    assertThatCode(() -> command.executar(evento))
        .as("renovacao inexistente deve ser ignorada sem lancar excecao")
        .doesNotThrowAnyException();

    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve aprovar a renovacao ao processar evento de renovacao aprovada")
  void deveAprovarRenovacaoAoProcessarEventoAprovado() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    assertThat(renovacao.getStatus())
        .as("renovacao pendente deve ir para APROVADA ao processar aprovacao")
        .isEqualTo(StatusRenovacao.APROVADA);
  }

  @Test
  @DisplayName("Deve tratar como no-op idempotente evento sobre renovacao ja aprovada")
  void deveTratarComoNoOpEventoSobreRenovacaoJaAprovada() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    renovacao.aprovar();
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    assertThatCode(() -> command.executar(evento))
        .as("evento tardio sobre renovacao ja aprovada nao deve lancar")
        .doesNotThrowAnyException();

    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve gravar AssinaturaRenovada na outbox ao aprovar a renovacao")
  void deveGravarAssinaturaRenovadaNaOutboxAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(captor.capture());
    assertThat(captor.getValue().getEventType())
        .as("evento de saida deve ser AssinaturaRenovada")
        .isEqualTo("AssinaturaRenovada");
    assertThat(captor.getValue().getAggregateType())
        .as("agregado de origem deve ser Renovacao")
        .isEqualTo("Renovacao");
    assertThat(captor.getValue().getAggregateId())
        .as("aggregateId deve ser o uuid da renovacao resolvida")
        .isEqualTo(UUID.fromString(renovacao.getUuid()));
  }

  @Test
  @DisplayName("Deve carimbar o instante do evento de saida com o relogio injetado")
  void deveCarimbarInstanteDoEventoDeSaidaComRelogioInjetado() throws Exception {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    Instant instanteFixo = Instant.parse("2026-03-10T09:30:00Z");
    Clock relogioFixo = Clock.fixed(instanteFixo, ZoneOffset.UTC);
    ProcessarRenovacaoResultado commandComRelogio =
        new ProcessarRenovacaoResultado(
            renovacaoRepository,
            assinaturaRepository,
            renovacaoEventoProcessadoRepository,
            usuarioRepository,
            cacheVersionado,
            outboxRepository,
            jsonMapper,
            relogioFixo,
            CICLO_MS_PADRAO);
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    commandComRelogio.executar(evento);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(captor.capture());
    AssinaturaRenovada eventoSaida =
        jsonMapper.readValue(captor.getValue().getPayload(), AssinaturaRenovada.class);
    assertThat(eventoSaida.ocorridoEm())
        .as("ocorridoEm do evento de saida deve vir do relogio injetado")
        .isEqualTo(instanteFixo);
  }

  @Test
  @DisplayName("Deve suspender a assinatura ao processar evento de tentativas esgotadas")
  void deveSuspenderAssinaturaAoProcessarEventoEsgotado() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            2);

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("assinatura em renovacao esgotada deve ir para SUSPENSA")
        .isEqualTo(StatusAssinatura.SUSPENSA);
  }

  @Test
  @DisplayName("Deve esgotar as tentativas da renovacao ao processar evento esgotado")
  void deveEsgotarTentativasDaRenovacaoAoProcessarEventoEsgotado() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            2);

    command.executar(evento);

    assertThat(renovacao.getStatus())
        .as("renovacao pendente deve ir para TENTATIVAS_ESGOTADA ao processar esgotamento")
        .isEqualTo(StatusRenovacao.TENTATIVAS_ESGOTADA);
  }

  @Test
  @DisplayName("Deve gravar AssinaturaSuspensa na outbox ao processar evento esgotado")
  void deveGravarAssinaturaSuspensaNaOutboxAoProcessarEventoEsgotado() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            2);

    command.executar(evento);

    ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(captor.capture());
    assertThat(captor.getValue().getEventType())
        .as("evento de saida deve ser AssinaturaSuspensa")
        .isEqualTo("AssinaturaSuspensa");
    assertThat(captor.getValue().getAggregateType())
        .as("agregado de origem deve ser Renovacao")
        .isEqualTo("Renovacao");
    assertThat(captor.getValue().getAggregateId())
        .as("aggregateId deve ser o uuid da renovacao esgotada")
        .isEqualTo(UUID.fromString(renovacao.getUuid()));
  }

  @Test
  @DisplayName("Deve tratar como no-op idempotente evento esgotado sobre renovacao ja esgotada")
  void deveTratarComoNoOpEventoEsgotadoSobreRenovacaoJaEsgotada() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    renovacao.esgotarTentativas();
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            2);

    assertThatCode(() -> command.executar(evento))
        .as("evento tardio sobre renovacao ja esgotada nao deve lancar")
        .doesNotThrowAnyException();

    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve registrar o eventId processado apos aprovar a renovacao com sucesso")
  void deveRegistrarEventIdProcessadoAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    UUID eventId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            eventId,
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    ArgumentCaptor<RenovacaoEventoProcessado> captor =
        ArgumentCaptor.forClass(RenovacaoEventoProcessado.class);
    verify(renovacaoEventoProcessadoRepository).save(captor.capture());
    assertThat(captor.getValue().getEventId())
        .as("evento processado com sucesso deve ser registrado pelo eventId para idempotencia")
        .isEqualTo(eventId);
  }

  @Test
  @DisplayName("Deve ignorar redelivery de evento esgotado ja processado sem buscar a renovacao")
  void deveIgnorarRedeliveryEsgotadoSemBuscarRenovacao() {
    UUID eventId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    when(renovacaoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            eventId,
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.randomUUID(),
            2);

    command.executar(evento);

    verify(renovacaoRepository, never()).buscarPorUuidParaAtualizacao(any(String.class));
  }

  @Test
  @DisplayName("Deve ignorar evento esgotado para renovacao inexistente sem registrar idempotencia")
  void deveIgnorarEventoEsgotadoParaRenovacaoInexistenteSemRegistrarIdempotencia() {
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(any(String.class)))
        .thenReturn(Optional.empty());
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.randomUUID(),
            2);

    assertThatCode(() -> command.executar(evento))
        .as("renovacao inexistente deve ser ignorada sem lancar excecao")
        .doesNotThrowAnyException();

    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve tratar como no-op assinatura dona inexistente ao esgotar tentativas")
  void deveTratarComoNoOpAssinaturaInexistenteAoEsgotarTentativas() {
    Renovacao renovacao = new Renovacao(42L, LocalDate.of(2026, 2, 15), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.empty());
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            2);

    assertThatCode(() -> command.executar(evento))
        .as("assinatura dona inexistente deve ser ignorada sem lancar excecao")
        .doesNotThrowAnyException();

    verify(outboxRepository, never()).save(any(OutboxEvent.class));
    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName("Deve tratar como no-op assinatura dona inexistente ao aprovar a renovacao")
  void deveTratarComoNoOpAssinaturaInexistenteAoAprovarRenovacao() {
    Renovacao renovacao = new Renovacao(42L, LocalDate.of(2026, 2, 15), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.empty());
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    assertThatCode(() -> command.executar(evento))
        .as("assinatura dona inexistente deve ser ignorada sem lancar excecao")
        .doesNotThrowAnyException();

    verify(outboxRepository, never()).save(any(OutboxEvent.class));
    verify(renovacaoEventoProcessadoRepository, never()).save(any(RenovacaoEventoProcessado.class));
  }

  @Test
  @DisplayName(
      "Deve tratar como no-op idempotente quando save do evento processado viola indice unico"
          + " (race de rebalance)")
  void deveTratarComoNoOpQuandoSaveDoEventoProcessadoViolaIndiceUnico() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    when(renovacaoEventoProcessadoRepository.save(any(RenovacaoEventoProcessado.class)))
        .thenThrow(new DataIntegrityViolationException("uq_renovacao_evento_processado_event_id"));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    assertThatCode(() -> command.executar(evento))
        .as(
            "violacao do indice unico no save concorrente deve ser absorvida como no-op"
                + " idempotente, sem propagar para o consumer Kafka")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Deve invalidar o cache da listagem do dono ao aprovar a renovacao")
  void deveInvalidarCacheDaListagemAoAprovarRenovacao() {
    Assinatura assinatura = assinaturaDona();
    Usuario dono = dono(7L);
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(7L)).thenReturn(Optional.of(dono));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    verify(cacheVersionado).invalidarAposCommit("assinatura:list:versao:" + dono.getUuid());
  }

  @Test
  @DisplayName("Deve invalidar o cache da listagem do dono ao esgotar as tentativas")
  void deveInvalidarCacheDaListagemAoEsgotarTentativas() {
    Assinatura assinatura = assinaturaDona();
    Usuario dono = dono(7L);
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(7L)).thenReturn(Optional.of(dono));
    RenovacaoTentativasEsgotadas evento =
        new RenovacaoTentativasEsgotadas(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.fromString(assinatura.getUuid()),
            3);

    command.executar(evento);

    verify(cacheVersionado).invalidarAposCommit("assinatura:list:versao:" + dono.getUuid());
  }

  @Test
  @DisplayName("Nao deve invalidar o cache em redelivery de evento ja processado")
  void naoDeveInvalidarCacheEmRedelivery() {
    UUID eventId = UUID.randomUUID();
    when(renovacaoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            eventId,
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    verify(cacheVersionado, never()).invalidarAposCommit(any());
  }

  @Test
  @DisplayName("Nao deve invalidar o cache para assinatura dona inexistente")
  void naoDeveInvalidarCacheParaAssinaturaDonaInexistente() {
    Renovacao renovacao = new Renovacao(42L, LocalDate.of(2026, 2, 15), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.empty());
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    verify(cacheVersionado, never()).invalidarAposCommit(any());
  }

  private Assinatura assinaturaDona() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    return assinatura;
  }

  private static Usuario dono(Long id) {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(id);
    return usuario;
  }
}
