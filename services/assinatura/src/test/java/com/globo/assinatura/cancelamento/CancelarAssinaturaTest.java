package com.globo.assinatura.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.globo.assinatura.assinatura.AcessoNegadoException;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.cancelamento.api.CancelamentoResponse;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.outbox.OutboxStatus;
import com.globo.assinatura.security.UsuarioAutenticado;
import com.globo.assinatura.shared.contrato.AssinaturaCancelada;
import com.globo.assinatura.shared.contrato.CancelamentoAgendado;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
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
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CancelarAssinaturaTest {

  @Mock private AssinaturaRepository assinaturaRepository;

  @Mock private UsuarioRepository usuarioRepository;

  @Mock private OutboxRepository outboxRepository;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private CancelarAssinatura command;

  @BeforeEach
  void setUp() {
    command =
        new CancelarAssinatura(
            assinaturaRepository,
            usuarioRepository,
            outboxRepository,
            jsonMapper,
            Clock.systemUTC());
  }

  @Test
  @DisplayName("Deve carimbar o instante do evento com o relogio injetado")
  void deveCarimbarInstanteDoEventoComRelogioInjetado() throws Exception {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));
    Instant instanteFixo = Instant.parse("2026-01-15T10:00:00Z");
    command =
        new CancelarAssinatura(
            assinaturaRepository,
            usuarioRepository,
            outboxRepository,
            jsonMapper,
            Clock.fixed(instanteFixo, ZoneOffset.UTC));

    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    command.executar(assinatura.getUuid(), principal);

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    CancelamentoAgendado evento =
        jsonMapper.readValue(capturado.getValue().getPayload(), CancelamentoAgendado.class);
    assertThat(evento.ocorridoEm())
        .as("Instante do evento carimbado pelo relogio injetado")
        .isEqualTo(instanteFixo);
  }

  @Test
  @DisplayName("Deve agendar cancelamento de assinatura ativa do proprio dono")
  void deveAgendarCancelamentoDeAssinaturaAtivaDoProprioDono() throws Exception {
    LocalDate inicioCiclo = LocalDate.of(2026, 1, 1);
    LocalDate fimCiclo = LocalDate.of(2026, 2, 1);
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.ativar(inicioCiclo, fimCiclo);
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));

    CancelamentoResponse resposta = command.executar(assinatura.getUuid(), principal);

    assertThat(resposta.id()).as("Uuid da assinatura cancelada").isEqualTo(assinatura.getUuid());
    assertThat(resposta.status())
        .as("Status apos o cancelamento agendado")
        .isEqualTo(StatusAssinatura.ATIVA);
    assertThat(resposta.renovacaoAutomatica()).as("Renovacao automatica desligada").isFalse();
    assertThat(resposta.acessoAte()).as("Acesso preservado ate o fim do ciclo").isEqualTo(fimCiclo);

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent outboxEvent = capturado.getValue();
    assertThat(outboxEvent.getEventType())
        .as("Tipo do evento do cancelamento agendado")
        .isEqualTo("CancelamentoAgendado");
    assertThat(outboxEvent.getStatus())
        .as("Evento nasce pendente")
        .isEqualTo(OutboxStatus.PENDENTE);

    CancelamentoAgendado evento =
        jsonMapper.readValue(outboxEvent.getPayload(), CancelamentoAgendado.class);
    assertThat(evento.assinaturaId())
        .as("Assinatura identificada no evento")
        .isEqualTo(UUID.fromString(assinatura.getUuid()));
    assertThat(evento.fimCiclo()).as("Fim do ciclo preservado no evento").isEqualTo(fimCiclo);
    assertThat(evento.status()).as("Status preservado no evento").isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve cancelar imediatamente assinatura suspensa sem preservar o acesso")
  void deveCancelarImediatamenteAssinaturaSuspensaSemPreservarAcesso() throws Exception {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    assinatura.iniciarRenovacao();
    assinatura.suspender();
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));

    CancelamentoResponse resposta = command.executar(assinatura.getUuid(), principal);

    assertThat(resposta.status())
        .as("Status apos o cancelamento imediato")
        .isEqualTo(StatusAssinatura.CANCELADA);
    assertThat(resposta.acessoAte()).as("Sem acesso a preservar no cancelamento imediato").isNull();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent outboxEvent = capturado.getValue();
    AssinaturaCancelada evento =
        jsonMapper.readValue(outboxEvent.getPayload(), AssinaturaCancelada.class);
    assertThat(evento.fimCiclo()).as("Sem fim de ciclo no evento").isNull();
  }

  @Test
  @DisplayName("Deve registrar log do cancelamento agendado")
  void deveRegistrarLogDoCancelamentoAgendado() {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.ativar(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));
    Logger logger = (Logger) LoggerFactory.getLogger(CancelarAssinatura.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      command.executar(assinatura.getUuid(), principal);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .as("log do cancelamento agendado")
        .anyMatch(
            evento ->
                evento.getLevel() == Level.INFO
                    && evento.getKeyValuePairs().stream()
                        .anyMatch(
                            par ->
                                "event".equals(par.key)
                                    && "cancelamento_agendado".equals(par.value)));
  }

  @Test
  @DisplayName("Deve registrar log do cancelamento idempotente")
  void deveRegistrarLogDoCancelamentoIdempotente() {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.solicitarCancelamento();
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));
    Logger logger = (Logger) LoggerFactory.getLogger(CancelarAssinatura.class);
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      command.executar(assinatura.getUuid(), principal);
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .as("log do cancelamento idempotente")
        .anyMatch(
            evento ->
                evento.getLevel() == Level.DEBUG
                    && evento.getKeyValuePairs().stream()
                        .anyMatch(
                            par ->
                                "event".equals(par.key)
                                    && "cancelamento_idempotente".equals(par.value)));
  }

  @Test
  @DisplayName("Nao deve gravar evento ao cancelar assinatura ja cancelada")
  void naoDeveGravarEventoAoCancelarAssinaturaJaCancelada() {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    assinatura.solicitarCancelamento();
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));

    CancelamentoResponse resposta = command.executar(assinatura.getUuid(), principal);

    assertThat(resposta.status())
        .as("Status apos o cancelamento idempotente")
        .isEqualTo(StatusAssinatura.CANCELADA);
    verifyNoInteractions(outboxRepository);
  }

  @Test
  @DisplayName("Deve negar acesso ao cancelar assinatura de outro usuario")
  void deveNegarAcessoAoCancelarAssinaturaDeOutroUsuario() {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado invasor =
        new UsuarioAutenticado(
            "invasor@example.com", "22222222-2222-2222-2222-222222222222", "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));

    assertThatThrownBy(() -> command.executar(assinatura.getUuid(), invasor))
        .as("Cancelamento de assinatura alheia negado")
        .isInstanceOf(AcessoNegadoException.class);
    verifyNoInteractions(outboxRepository);
  }

  @Test
  @DisplayName("Deve lancar excecao ao cancelar assinatura inexistente")
  void deveLancarExcecaoAoCancelarAssinaturaInexistente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado(
            "fulano@example.com", "11111111-1111-1111-1111-111111111111", "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> command.executar("99999999-9999-9999-9999-999999999999", principal))
        .as("Cancelamento de assinatura inexistente negado")
        .isInstanceOf(AssinaturaNaoEncontradaException.class);
    verifyNoInteractions(outboxRepository);
  }

  @Test
  @DisplayName("Deve cancelar imediatamente assinatura aguardando pagamento do proprio dono")
  void deveCancelarImediatamenteAssinaturaAguardandoPagamentoDoProprioDono() throws Exception {
    Assinatura assinatura = new Assinatura(42L, Plano.BASICO);
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    dono.setId(42L);
    dono.setUuid("11111111-1111-1111-1111-111111111111");
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", dono.getUuid(), "ROLE_USUARIO");
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));

    CancelamentoResponse resposta = command.executar(assinatura.getUuid(), principal);

    assertThat(resposta.status())
        .as("Status apos o cancelamento imediato")
        .isEqualTo(StatusAssinatura.CANCELADA);
    assertThat(resposta.renovacaoAutomatica()).as("Renovacao automatica desligada").isFalse();
    assertThat(resposta.acessoAte()).as("Sem acesso preservado sem ciclo pago").isNull();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent outboxEvent = capturado.getValue();
    assertThat(outboxEvent.getEventType())
        .as("Tipo do evento do cancelamento imediato")
        .isEqualTo("AssinaturaCancelada");
    assertThat(outboxEvent.getStatus())
        .as("Evento nasce pendente")
        .isEqualTo(OutboxStatus.PENDENTE);

    AssinaturaCancelada evento =
        jsonMapper.readValue(outboxEvent.getPayload(), AssinaturaCancelada.class);
    assertThat(evento.assinaturaId())
        .as("Assinatura identificada no evento")
        .isEqualTo(UUID.fromString(assinatura.getUuid()));
    assertThat(evento.status())
        .as("Status cancelado no evento")
        .isEqualTo(StatusAssinatura.CANCELADA);
    assertThat(evento.fimCiclo()).as("Sem fim de ciclo no evento").isNull();
  }
}
