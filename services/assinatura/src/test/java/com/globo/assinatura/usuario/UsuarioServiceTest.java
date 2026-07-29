package com.globo.assinatura.usuario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

  @Mock private UsuarioRepository usuarioRepository;

  @InjectMocks private UsuarioService usuarioService;

  @Test
  @DisplayName("Deve cadastrar cliente novo e retornar o uuid atribuido pela entidade")
  void cadastraClienteNovoRetornaIdentificador() {
    final String resultado = usuarioService.cadastrar("Fulano", "novo@example.com");

    ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
    verify(usuarioRepository).save(capturado.capture());
    Usuario persistido = capturado.getValue();

    assertThat(persistido.getNome()).as("Nome do usuario persistido").isEqualTo("Fulano");
    assertThat(persistido.getEmail())
        .as("Email do usuario persistido")
        .isEqualTo("novo@example.com");
    assertThat(resultado).as("Uuid retornado").isEqualTo(persistido.getUuid());
    assertThatCode(() -> UUID.fromString(resultado))
        .as("Uuid retornado deve ter formato valido")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Deve lancar conflito ao cadastrar cliente com email ja existente")
  void cadastraClienteComEmailExistenteLancaConflito() {
    org.mockito.Mockito.when(usuarioRepository.existsByEmail("existente@example.com"))
        .thenReturn(true);

    assertThatThrownBy(() -> usuarioService.cadastrar("Fulano", "existente@example.com"))
        .as("Email duplicado deve gerar conflito de dominio")
        .isInstanceOf(EmailJaCadastradoException.class);
  }
}
