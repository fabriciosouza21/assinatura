package com.globo.assinatura.consulta;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de consulta da assinatura ativa do usuario.
 *
 * <p>Devolve a representacao da assinatura ativa do usuario consultando o cache distribuido antes
 * do banco (cache-aside); em miss consulta o banco e popula o cache. Responsavel apenas por
 * leitura.
 */
@Service
public class ConsultarAssinaturaAtiva {

  private final AssinaturaRepository assinaturaRepository;
  private final UsuarioRepository usuarioRepository;
  private final AssinaturaAtivaCache assinaturaAtivaCache;

  /**
   * Constroi a query com os repositorios e o cache injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param assinaturaAtivaCache cache distribuido da assinatura ativa
   */
  public ConsultarAssinaturaAtiva(
      AssinaturaRepository assinaturaRepository,
      UsuarioRepository usuarioRepository,
      AssinaturaAtivaCache assinaturaAtivaCache) {
    this.assinaturaRepository = assinaturaRepository;
    this.usuarioRepository = usuarioRepository;
    this.assinaturaAtivaCache = assinaturaAtivaCache;
  }

  /**
   * Consulta a assinatura ativa do usuario.
   *
   * <p>Em acerto de cache devolve a representacao armazenada sem consultar o banco; em miss
   * consulta o banco e popula o cache. Falha do cache degrada para o banco.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @return a representacao da assinatura ativa, ou vazio quando ausente
   */
  @Transactional(readOnly = true)
  public Optional<AssinaturaResponse> executar(String usuarioUuid) {
    Optional<AssinaturaResponse> possivelCacheada = assinaturaAtivaCache.recuperar(usuarioUuid);
    if (possivelCacheada.isPresent()) {
      return possivelCacheada;
    }
    Optional<AssinaturaResponse> doBanco = buscarNoBanco(usuarioUuid);
    doBanco.ifPresent(resposta -> assinaturaAtivaCache.popular(usuarioUuid, resposta));
    return doBanco;
  }

  private Optional<AssinaturaResponse> buscarNoBanco(String usuarioUuid) {
    return usuarioRepository
        .findByUuid(usuarioUuid)
        .map(Usuario::getId)
        .flatMap(id -> assinaturaRepository.findByUsuarioIdAndStatus(id, StatusAssinatura.ATIVA))
        .map(assinatura -> toResponse(assinatura, usuarioUuid));
  }

  private AssinaturaResponse toResponse(Assinatura assinatura, String usuarioUuid) {
    return new AssinaturaResponse(
        assinatura.getUuid(),
        usuarioUuid,
        assinatura.getPlano(),
        assinatura.getDataInicio(),
        assinatura.getDataExpiracao(),
        assinatura.getStatus(),
        assinatura.getInicioCiclo(),
        assinatura.getFimCiclo(),
        assinatura.getProximaRenovacaoEm(),
        assinatura.isRenovacaoAutomatica());
  }
}
