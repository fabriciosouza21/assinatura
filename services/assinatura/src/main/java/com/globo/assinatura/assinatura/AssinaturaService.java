package com.globo.assinatura.assinatura;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.List;
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

  private static final List<StatusAssinatura> STATUS_ABERTOS =
      List.of(StatusAssinatura.AGUARDANDO_PAGAMENTO, StatusAssinatura.ATIVA);

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
    Usuario usuario =
        usuarioRepository.findByUuid(usuarioUuid).orElseThrow(UsuarioNaoEncontradoException::new);
    if (assinaturaRepository.existsByUsuarioIdAndStatusIn(usuario.getId(), STATUS_ABERTOS)) {
      throw new AssinaturaAbertaException();
    }
    Assinatura assinatura = new Assinatura(usuario.getId(), plano);
    return assinaturaRepository.save(assinatura);
  }

  /**
   * Consulta uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a representacao completa da assinatura, com o uuid publico do usuario dono
   */
  @Transactional(readOnly = true)
  public AssinaturaResponse consultar(String uuid) {
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
