package com.globo.assinatura.cadastro;

import com.globo.assinatura.usuario.Credencial;
import com.globo.assinatura.usuario.CredencialRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra os casos de uso do dominio de usuario.
 *
 * <p>Atualmente expoe o cadastro de um usuario novo, criando na mesma transacao o {@link Usuario}
 * de dominio e o {@link Credencial} autenticavel ligado, devolvendo o uuid publico atribuido pela
 * entidade.
 */
@Service
public class CadastrarUsuario {

  private static final String ROLE_CLIENT = "ROLE_CLIENT";

  private final UsuarioRepository usuarioRepository;
  private final CredencialRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  /**
   * Constroi o servico com o repositorio de usuario, o repositorio de credenciais e o codificador
   * de senhas injetados.
   *
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param userRepository repositorio de persistencia de credenciais
   * @param passwordEncoder codificador de senhas (BCrypt)
   */
  public CadastrarUsuario(
      UsuarioRepository usuarioRepository,
      CredencialRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.usuarioRepository = usuarioRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Cadastra um usuario novo.
   *
   * <p>Cria, na mesma transacao, o {@link Usuario} de dominio e o {@link Credencial} autenticavel
   * com {@code username = email}, senha hasheada com BCrypt, {@code role = ROLE_CLIENT} e linkado
   * ao {@link Usuario} via {@code usuario_id}.
   *
   * @param nome nome do usuario
   * @param email email do usuario
   * @param senha senha em texto plano do usuario, hasheada antes de persistir
   * @return uuid publico atribuido ao usuario persistido
   * @throws EmailJaCadastradoException se o email informado ja estiver cadastrado
   */
  @Transactional
  public String cadastrar(String nome, String email, String senha) {
    if (usuarioRepository.existsByEmail(email)) {
      throw new EmailJaCadastradoException();
    }

    Usuario usuario = new Usuario(nome, email);
    usuarioRepository.save(usuario);

    Credencial user =
        new Credencial(email, passwordEncoder.encode(senha), ROLE_CLIENT, usuario.getId());
    userRepository.save(user);

    return usuario.getUuid();
  }
}
