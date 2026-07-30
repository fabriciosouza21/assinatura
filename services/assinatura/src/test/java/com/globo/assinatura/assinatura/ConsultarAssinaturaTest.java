package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
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

  @Test
  @DisplayName("Deve consultar assinatura existente retornando o uuid do usuario")
  void deveConsultarAssinaturaExistenteRetornandoUuidDoUsuario() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    when(assinaturaRepository.findByUuid(assinatura.getUuid())).thenReturn(Optional.of(assinatura));
    when(usuarioRepository.findById(42L)).thenReturn(Optional.of(usuario));

    AssinaturaResponse resposta = query.executar(assinatura.getUuid());

    assertThat(resposta.id()).as("Uuid da assinatura").isEqualTo(assinatura.getUuid());
    assertThat(resposta.usuarioId()).as("Uuid publico do usuario").isEqualTo(usuario.getUuid());
    assertThat(resposta.plano()).as("Plano").isEqualTo(Plano.PREMIUM);
    assertThat(resposta.status()).as("Status").isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
    assertThat(resposta.dataInicio()).as("Data inicio nula enquanto aguarda").isNull();
    assertThat(resposta.dataExpiracao()).as("Data expiracao nula enquanto aguarda").isNull();
  }

  @Test
  @DisplayName("Deve lancar nao encontrado ao consultar assinatura inexistente")
  void deveLancarNaoEncontradoAoConsultarAssinaturaInexistente() {
    when(assinaturaRepository.findByUuid("uuid-inexistente")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> query.executar("uuid-inexistente"))
        .as("Assinatura inexistente deve gerar nao encontrado")
        .isInstanceOf(AssinaturaNaoEncontradaException.class);
  }
}
