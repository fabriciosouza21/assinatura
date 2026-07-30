package com.globo.assinatura.assinatura;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de consulta de assinatura.
 *
 * <p>Recupera a representacao completa de uma assinatura pelo uuid publico, resolvendo o uuid do
 * usuario dono a partir do identificador interno. Responsavel apenas por leitura.
 */
@Service
public class ConsultarAssinatura {

  private final AssinaturaRepository assinaturaRepository;
  private final UsuarioRepository usuarioRepository;

  /**
   * Constroi a query com os repositorios injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param usuarioRepository repositorio de persistencia de usuarios
   */
  public ConsultarAssinatura(
      AssinaturaRepository assinaturaRepository, UsuarioRepository usuarioRepository) {
    this.assinaturaRepository = assinaturaRepository;
    this.usuarioRepository = usuarioRepository;
  }

  /**
   * Consulta uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a representacao completa da assinatura, com o uuid publico do usuario dono
   * @throws AssinaturaNaoEncontradaException se o uuid nao corresponder a uma assinatura
   */
  @Transactional(readOnly = true)
  public AssinaturaResponse executar(String uuid) {
    Assinatura assinatura =
        assinaturaRepository.findByUuid(uuid).orElseThrow(AssinaturaNaoEncontradaException::new);
    Usuario usuario = usuarioRepository.findById(assinatura.getUsuarioId()).orElseThrow();
    return new AssinaturaResponse(
        assinatura.getUuid(),
        usuario.getUuid(),
        assinatura.getPlano(),
        assinatura.getDataInicio(),
        assinatura.getDataExpiracao(),
        assinatura.getStatus());
  }
}
