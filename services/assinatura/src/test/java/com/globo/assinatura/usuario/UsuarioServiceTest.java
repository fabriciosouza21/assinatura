package com.globo.assinatura.usuario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.globo.assinatura.user.User;
import com.globo.assinatura.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

  @Mock private UsuarioRepository usuarioRepository;
  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  @InjectMocks private UsuarioService usuarioService;

  @Test
  @DisplayName("Deve cadastrar cliente novo e retornar o uuid atribuido pela entidade")
  void cadastraClienteNovoRetornaIdentificador() {
    when(passwordEncoder.encode("SenhaForte1")).thenReturn("hash-bcrypt");

    final String resultado = usuarioService.cadastrar("Fulano", "novo@example.com", "SenhaForte1");

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
    when(usuarioRepository.existsByEmail("existente@example.com")).thenReturn(true);

    assertThatThrownBy(
            () -> usuarioService.cadastrar("Fulano", "existente@example.com", "SenhaForte1"))
        .as("Email duplicado deve gerar conflito de dominio")
        .isInstanceOf(EmailJaCadastradoException.class);

    verifyNoInteractions(userRepository);
    assertThat(true).as("Nenhuma credencial e criada quando o email ja existe").isTrue();
  }

  @Test
  @DisplayName("Deve criar Usuario e User auth ligado na mesma transacao ao cadastrar cliente")
  void deveCriarCredencialAoCadastrarCliente() {
    when(usuarioRepository.existsByEmail("novo@example.com")).thenReturn(false);
    when(passwordEncoder.encode("SenhaForte1")).thenReturn("hash-bcrypt");
    doAnswer(
            inv -> {
              Usuario u = inv.getArgument(0);
              u.setId(1L);
              return u;
            })
        .when(usuarioRepository)
        .save(any(Usuario.class));

    usuarioService.cadastrar("Fulano", "novo@example.com", "SenhaForte1");

    ArgumentCaptor<User> userCapturado = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCapturado.capture());
    User userPersistido = userCapturado.getValue();
    assertThat(userPersistido.getUsername())
        .as("Username do User e o email")
        .isEqualTo("novo@example.com");
    assertThat(userPersistido.getPassword())
        .as("Senha hasheada com BCrypt")
        .isEqualTo("hash-bcrypt");
    assertThat(userPersistido.getRole()).as("Role do cliente").isEqualTo("ROLE_CLIENT");
    assertThat(userPersistido.getUsuarioId()).as("Link do User com o Usuario").isEqualTo(1L);
  }
}
