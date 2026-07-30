package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssinaturaServiceTest {

  @Mock private UsuarioRepository usuarioRepository;

  @Mock private AssinaturaRepository assinaturaRepository;

  @InjectMocks private AssinaturaService service;

  @Test
  @DisplayName("Deve solicitar assinatura para usuario sem assinatura aberta")
  void deveSolicitarAssinaturaParaUsuarioSemAssinaturaAberta() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(42L);
    when(usuarioRepository.findByUuid("uuid-usuario")).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.save(any(Assinatura.class))).thenAnswer(inv -> inv.getArgument(0));

    Assinatura resultado = service.solicitar("uuid-usuario", Plano.PREMIUM);

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
  @DisplayName("Deve lancar excecao ao solicitar para usuario inexistente")
  void deveLancarExcecaoAoSolicitarParaUsuarioInexistente() {
    when(usuarioRepository.findByUuid("uuid-inexistente")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.solicitar("uuid-inexistente", Plano.BASICO))
        .as("Usuario inexistente deve gerar nao encontrado")
        .isInstanceOf(UsuarioNaoEncontradoException.class);
  }
}
