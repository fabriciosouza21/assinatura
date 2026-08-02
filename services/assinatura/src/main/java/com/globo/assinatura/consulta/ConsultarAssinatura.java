package com.globo.assinatura.consulta;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de consulta de assinatura.
 *
 * <p>Recupera a representacao completa de uma assinatura pelo uuid publico, resolvendo o uuid do
 * usuario dono a partir do identificador interno. So devolve a assinatura ao proprio dono ou a um
 * administrador. Responsavel apenas por leitura.
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
   * @param principal identidade extraida do token JWT
   * @return a representacao completa da assinatura, com o uuid publico do usuario dono
   * @throws AssinaturaNaoEncontradaException se o uuid nao corresponder a uma assinatura, ou se a
   *     assinatura apontar para um usuario inexistente
   * @throws AcessoNegadoException se a assinatura pertencer a outro usuario e o solicitante nao for
   *     administrador
   */
  @Transactional(readOnly = true)
  public AssinaturaResponse executar(String uuid, UsuarioAutenticado principal) {
    Assinatura assinatura =
        assinaturaRepository.findByUuid(uuid).orElseThrow(AssinaturaNaoEncontradaException::new);
    Usuario usuario =
        usuarioRepository
            .findById(assinatura.getUsuarioId())
            .orElseThrow(AssinaturaNaoEncontradaException::new);
    if (!podeConsultar(usuario, principal)) {
      throw new AcessoNegadoException();
    }
    return new AssinaturaResponse(
        assinatura.getUuid(),
        usuario.getUuid(),
        assinatura.getPlano(),
        assinatura.getDataInicio(),
        assinatura.getDataExpiracao(),
        assinatura.getStatus(),
        assinatura.getInicioCiclo(),
        assinatura.getFimCiclo(),
        assinatura.getProximaRenovacaoEm(),
        assinatura.isRenovacaoAutomatica());
  }

  private boolean podeConsultar(Usuario dono, UsuarioAutenticado principal) {
    return principal.isAdmin() || dono.getUuid().equals(principal.usuarioId());
  }
}
