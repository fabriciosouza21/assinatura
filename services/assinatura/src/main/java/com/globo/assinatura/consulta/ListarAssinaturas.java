package com.globo.assinatura.consulta;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.consulta.api.AssinaturaLista;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de listagem de assinaturas.
 *
 * <p>Lista as assinaturas do usuario dono do token, paginadas e ordenadas da mais recente para a
 * mais antiga, consultando o cache distribuido antes do banco (cache-aside). Usuario sem
 * assinaturas, ou cujo uuid nao corresponda a um usuario cadastrado, recebe pagina vazia com total
 * zero. Responsavel apenas por leitura.
 */
@Service
public class ListarAssinaturas {

  private final AssinaturaRepository assinaturaRepository;
  private final UsuarioRepository usuarioRepository;
  private final AssinaturaListaCache assinaturaListaCache;

  /**
   * Constroi a query com os repositorios e o cache injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param assinaturaListaCache cache distribuido da listagem
   */
  public ListarAssinaturas(
      AssinaturaRepository assinaturaRepository,
      UsuarioRepository usuarioRepository,
      AssinaturaListaCache assinaturaListaCache) {
    this.assinaturaRepository = assinaturaRepository;
    this.usuarioRepository = usuarioRepository;
    this.assinaturaListaCache = assinaturaListaCache;
  }

  /**
   * Lista as assinaturas do usuario do token, paginadas da mais recente para a mais antiga.
   *
   * <p>Em acerto de cache devolve a pagina armazenada sem consultar o banco; em miss consulta o
   * banco e popula o cache. Falha do Redis degrada para o banco.
   *
   * @param usuarioUuid uuid publico do usuario dono, extraido do token JWT
   * @param page pagina corrente, comecando em 0
   * @param size tamanho da pagina
   * @return pagina com os itens do usuario e o total de assinaturas
   */
  @Transactional(readOnly = true)
  public AssinaturaLista executar(String usuarioUuid, int page, int size) {
    Optional<AssinaturaLista> possivelCacheada =
        assinaturaListaCache.recuperar(usuarioUuid, page, size);
    if (possivelCacheada.isPresent()) {
      return possivelCacheada.get();
    }
    AssinaturaLista lista = buscarNoBanco(usuarioUuid, page, size);
    assinaturaListaCache.popular(usuarioUuid, page, size, lista);
    return lista;
  }

  private AssinaturaLista buscarNoBanco(String usuarioUuid, int page, int size) {
    Optional<Usuario> possivelUsuario = usuarioRepository.findByUuid(usuarioUuid);
    if (possivelUsuario.isEmpty()) {
      return new AssinaturaLista(List.of(), page, size, 0);
    }
    Page<Assinatura> pagina =
        assinaturaRepository.findByUsuarioIdOrderByIdDesc(
            possivelUsuario.get().getId(), PageRequest.of(page, size));
    List<AssinaturaResponse> items =
        pagina.getContent().stream()
            .map(assinatura -> AssinaturaResponse.of(assinatura, usuarioUuid))
            .toList();
    return new AssinaturaLista(items, page, size, pagina.getTotalElements());
  }
}
