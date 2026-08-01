package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.messaging.event.AssinaturaRenovada;
import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import com.globo.assinatura.messaging.event.RenovacaoTentativasEsgotadas;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.renovacao.RenovacaoEventoProcessado;
import com.globo.assinatura.renovacao.RenovacaoEventoProcessadoRepository;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProcessarRenovacaoResultadoTest {

  @Mock private RenovacaoRepository renovacaoRepository;
  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository;
  @Mock private OutboxRepository outboxRepository;

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
            outboxRepository,
            jsonMapper,
            relogio);
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
  @DisplayName("Deve avancar o fim do ciclo em um mes ao aprovar a renovacao")
  void deveAvancarFimCicloEmUmMesAoAprovarRenovacao() {
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
        .as("novo fim de ciclo deve avancar um mes sobre o anterior")
        .isEqualTo(fimCicloAntes.plusMonths(1));
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
            outboxRepository,
            jsonMapper,
            relogioFixo);
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
}
