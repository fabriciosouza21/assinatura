package com.globo.assinatura.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
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
import java.time.LocalDate;
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
            assinaturaRepository, usuarioRepository, outboxRepository, jsonMapper);
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
