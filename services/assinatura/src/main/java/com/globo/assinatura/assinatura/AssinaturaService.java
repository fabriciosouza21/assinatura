package com.globo.assinatura.assinatura;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra os casos de uso do dominio de assinatura.
 *
 * <p>Atualmente expoe a solicitacao de uma assinatura nova, resolvendo o usuario dono pelo uuid
 * publico e delegando a persistencia ao repositorio.
 */
@Service
public class AssinaturaService {

  private final UsuarioRepository usuarioRepository;
  private final AssinaturaRepository assinaturaRepository;

  /**
   * Constroi o servico com os repositorios injetados.
   *
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   */
  public AssinaturaService(
      UsuarioRepository usuarioRepository, AssinaturaRepository assinaturaRepository) {
    this.usuarioRepository = usuarioRepository;
    this.assinaturaRepository = assinaturaRepository;
  }

  /**
   * Solicita uma assinatura para o usuario e o plano informados.
   *
   * @param usuarioUuid uuid publico do usuario que solicita a assinatura
   * @param plano plano contratado
   * @return a assinatura criada
   */
  @Transactional
  public Assinatura solicitar(String usuarioUuid, Plano plano) {
    Usuario usuario = usuarioRepository.findByUuid(usuarioUuid).orElseThrow();
    Assinatura assinatura = new Assinatura(usuario.getId(), plano);
    return assinaturaRepository.save(assinatura);
  }
}
