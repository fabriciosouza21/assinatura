package com.globo.assinatura.adesao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import com.globo.assinatura.shared.outbox.OutboxStatus;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.Optional;
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
class SolicitarAssinaturaTest {

  @Mock private UsuarioRepository usuarioRepository;

  @Mock private AssinaturaRepository assinaturaRepository;

  @Mock private OutboxRepository outboxRepository;

  private SolicitarAssinatura command;

  @BeforeEach
  void setUp() {
    command =
        new SolicitarAssinatura(
            usuarioRepository,
            assinaturaRepository,
            outboxRepository,
            JsonMapper.builder().build());
  }

  @Test
  @DisplayName("Deve solicitar assinatura para usuario sem assinatura aberta")
  void deveSolicitarAssinaturaParaUsuarioSemAssinaturaAberta() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    when(usuarioRepository.findByUuid("uuid-usuario")).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));

    Assinatura resultado = command.executar("uuid-usuario", Plano.PREMIUM);

    ArgumentCaptor<Assinatura> capturado = ArgumentCaptor.forClass(Assinatura.class);
    verify(assinaturaRepository).save(capturado.capture());
    Assinatura persistida = capturado.getValue();
    assertThat(persistida.getUsuarioId()).as("Usuario interno").isEqualTo(42L);
    assertThat(persistida.getPlano()).as("Plano contratado").isEqualTo(Plano.PREMIUM);
    assertThat(persistida.getStatus())
        .as("Status inicial")
        .isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    assertThat(resultado).as("Retorna a assinatura criada").isSameAs(persistida);
  }

  @Test
  @DisplayName("Deve lancar nao encontrado ao solicitar para usuario inexistente")
  void deveLancarNaoEncontradoAoSolicitarParaUsuarioInexistente() {
    when(usuarioRepository.findByUuid("uuid-inexistente")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> command.executar("uuid-inexistente", Plano.BASICO))
        .as("Usuario inexistente deve gerar nao encontrado")
        .isInstanceOf(UsuarioNaoEncontradoException.class);
  }

  @Test
  @DisplayName("Deve lancar conflito ao solicitar para usuario com assinatura aberta")
  void deveLancarConflitoAoSolicitarParaUsuarioComAssinaturaAberta() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    when(usuarioRepository.findByUuid("uuid-usuario")).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.existsByUsuarioIdAndStatusIn(eq(42L), any())).thenReturn(true);

    assertThatThrownBy(() -> command.executar("uuid-usuario", Plano.BASICO))
        .as("Assinatura aberta deve gerar conflito")
        .isInstanceOf(AssinaturaAbertaException.class);
  }

  @Test
  @DisplayName("Deve lancar conflito quando o insert viola o indice unico sob concorrencia")
  void deveLancarConflitoQuandoInsertViolaIndiceUnico() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    when(usuarioRepository.findByUuid("uuid-usuario")).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.existsByUsuarioIdAndStatusIn(eq(42L), any())).thenReturn(false);
    when(assinaturaRepository.save(any(Assinatura.class)))
        .thenThrow(new DataIntegrityViolationException("uq_assinatura_aberta_usuario"));

    assertThatThrownBy(() -> command.executar("uuid-usuario", Plano.BASICO))
        .as("Violacao do indice unico sob concorrencia deve gerar conflito")
        .isInstanceOf(AssinaturaAbertaException.class);
  }

  @Test
  @DisplayName("Deve gravar evento pendente na outbox ao solicitar assinatura")
  void deveGravarEventoPendenteNaOutbox() throws Exception {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    String usuarioUuid = "11111111-1111-1111-1111-111111111111";
    usuario.setUuid(usuarioUuid);
    when(usuarioRepository.findByUuid(usuarioUuid)).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));

    command.executar(usuarioUuid, Plano.PREMIUM);

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent evento = capturado.getValue();
    assertThat(evento.getEventType())
        .as("Tipo do evento da outbox")
        .isEqualTo("AssinaturaSolicitada");
    assertThat(evento.getStatus()).as("Evento nasce pendente").isEqualTo(OutboxStatus.PENDENTE);
  }

  @Test
  @DisplayName("Deve propagar falha da outbox para permitir rollback do insert da assinatura")
  void devePropagarFalhaDaOutboxParaRollbackDoInsert() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    String usuarioUuid = "11111111-1111-1111-1111-111111111111";
    usuario.setUuid(usuarioUuid);
    when(usuarioRepository.findByUuid(usuarioUuid)).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));
    when(outboxRepository.save(any(OutboxEvent.class)))
        .thenThrow(new RuntimeException("outbox indisponivel"));

    assertThatThrownBy(() -> command.executar(usuarioUuid, Plano.PREMIUM))
        .as("Falha ao gravar o evento deve propagar para que a transacao role back o insert")
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("outbox indisponivel");
  }
}
