package com.globo.assinatura.usuario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import java.util.UUID;
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
  void cadastraClienteNovoRetornaIdentificador() {
    final String resultado = usuarioService.cadastrar("Fulano", "novo@example.com");

    ArgumentCaptor<Usuario> capturado = ArgumentCaptor.forClass(Usuario.class);
    verify(usuarioRepository).save(capturado.capture());
    Usuario persistido = capturado.getValue();
    assertEquals("Fulano", persistido.getNome());
    assertEquals("novo@example.com", persistido.getEmail());
    assertEquals(persistido.getUuid(), resultado);
    UUID.fromString(resultado);
  }
}
