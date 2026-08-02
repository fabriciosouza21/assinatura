package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultarAssinaturaTest {

  @Mock private AssinaturaRepository assinaturaRepository;

  @Mock private UsuarioRepository usuarioRepository;

  @InjectMocks private ConsultarAssinatura query;

  private static UsuarioAutenticado dono(Usuario usuario) {
    return new UsuarioAutenticado(usuario.getEmail(), usuario.getUuid(), "ROLE_CLIENT");
  }

  @Test
  @DisplayName("Deve consultar assinatura do proprio usuario retornando o uuid do usuario")
  void deveConsultarAssinaturaDoProprioUsuarioRetornandoUuidDoUsuario() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(usuario));

    AssinaturaResponse resposta = query.executar(assinatura.getUuid(), dono(usuario));

    assertThat(resposta.id()).as("Uuid da assinatura").isEqualTo(assinatura.getUuid());
    assertThat(resposta.usuarioId()).as("Uuid publico do usuario").isEqualTo(usuario.getUuid());
    assertThat(resposta.plano()).as("Plano").isEqualTo(Plano.PREMIUM);
    assertThat(resposta.status()).as("Status").isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    assertThat(resposta.dataInicio()).as("Data inicio nula enquanto aguarda").isNull();
    assertThat(resposta.dataExpiracao()).as("Data expiracao nula enquanto aguarda").isNull();
  }

  @Test
  @DisplayName("Deve expor a proxima renovacao como instante preservando o horario")
  void deveExporProximaRenovacaoComoInstantePreservandoHorario() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    assinatura.ativar(
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), Instant.parse("2026-02-01T12:30:00Z"));
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(usuario));

    AssinaturaResponse resposta = query.executar(assinatura.getUuid(), dono(usuario));

    assertThat(resposta.proximaRenovacaoEm())
        .as("Instante da proxima renovacao preservado na consulta")
        .isEqualTo(Instant.parse("2026-02-01T12:30:00Z"));
    assertThat(resposta.fimCiclo())
        .as("Fim do ciclo continua exposto como data")
        .isEqualTo(LocalDate.of(2026, 2, 1));
  }

  @Test
  @DisplayName("Deve lancar nao encontrado ao consultar assinatura inexistente")
  void deveLancarNaoEncontradoAoConsultarAssinaturaInexistente() {
    when(assinaturaRepository.findByUuid("uuid-inexistente")).thenReturn(Optional.empty());
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", "usuario-uuid", "ROLE_CLIENT");

    assertThatThrownBy(() -> query.executar("uuid-inexistente", principal))
        .as("Assinatura inexistente deve gerar nao encontrado")
        .isInstanceOf(AssinaturaNaoEncontradaException.class);
  }

  @Test
  @DisplayName("Deve lancar acesso negado ao consultar assinatura de outro usuario")
  void deveLancarAcessoNegadoAoConsultarAssinaturaDeOutroUsuario() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));
    UsuarioAutenticado intruso =
        new UsuarioAutenticado("sicrano@example.com", "outro-uuid", "ROLE_CLIENT");

    assertThatThrownBy(() -> query.executar(assinatura.getUuid(), intruso))
        .as("Assinatura de terceiro deve gerar acesso negado")
        .isInstanceOf(AcessoNegadoException.class);
  }

  @Test
  @DisplayName("Deve permitir que administrador consulte assinatura de qualquer usuario")
  void devePermitirAdministradorConsultarAssinaturaDeQualquerUsuario() {
    Assinatura assinatura = new Assinatura(42L, Plano.FAMILIA);
    Usuario dono = new Usuario("Fulano", "fulano@example.com");
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(dono));
    UsuarioAutenticado admin = new UsuarioAutenticado("admin", null, UsuarioAutenticado.ROLE_ADMIN);

    AssinaturaResponse resposta = query.executar(assinatura.getUuid(), admin);

    assertThat(resposta.usuarioId())
        .as("Administrador enxerga a assinatura do dono")
        .isEqualTo(dono.getUuid());
  }

  @Test
  @DisplayName("Deve lancar nao encontrado quando a assinatura aponta para usuario inexistente")
  void deveLancarNaoEncontradoQuandoAssinaturaApontaParaUsuarioInexistente() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.empty());
    UsuarioAutenticado principal =
        new UsuarioAutenticado("fulano@example.com", "usuario-uuid", "ROLE_CLIENT");

    assertThatThrownBy(() -> query.executar(assinatura.getUuid(), principal))
        .as("Assinatura orfa deve gerar nao encontrado, nao erro de infraestrutura")
        .isInstanceOf(AssinaturaNaoEncontradaException.class);
  }
}
