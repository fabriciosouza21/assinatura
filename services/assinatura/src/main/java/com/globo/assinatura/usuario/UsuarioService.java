package com.globo.assinatura.usuario;

import org.springframework.stereotype.Service;

/**
 * Orquestra os casos de uso do dominio de usuario.
 *
 * <p>Atualmente expoe o cadastro de um usuario novo, delegando a persistencia ao repositorio e
 * devolvendo o uuid publico atribuido pela entidade.
 */
@Service
public class UsuarioService {

  private final UsuarioRepository usuarioRepository;

  /**
   * Constroi o servico com o repositorio de usuario injetado.
   *
   * @param usuarioRepository repositorio de persistencia de usuarios
   */
  public UsuarioService(UsuarioRepository usuarioRepository) {
    this.usuarioRepository = usuarioRepository;
  }

  /**
   * Cadastra um usuario novo.
   *
   * @param nome nome do usuario
   * @param email email do usuario
   * @return uuid publico atribuido ao usuario persistido
   * @throws EmailJaCadastradoException se o email informado ja estiver cadastrado
   */
  public String cadastrar(String nome, String email) {
    if (usuarioRepository.existsByEmail(email)) {
      throw new EmailJaCadastradoException(email);
    }

    Usuario usuario = new Usuario();
    usuario.setNome(nome);
    usuario.setEmail(email);
    usuarioRepository.save(usuario);

    return usuario.getUuid();
  }
}
