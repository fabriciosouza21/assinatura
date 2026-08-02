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
 * mais antiga. Usuario sem assinaturas, ou cujo uuid nao corresponda a um usuario cadastrado,
 * recebe pagina vazia com total zero. Responsavel apenas por leitura.
 */
@Service
public class ListarAssinaturas {

  private final AssinaturaRepository assinaturaRepository;
  private final UsuarioRepository usuarioRepository;

  /**
   * Constroi a query com os repositorios injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param usuarioRepository repositorio de persistencia de usuarios
   */
  public ListarAssinaturas(
      AssinaturaRepository assinaturaRepository, UsuarioRepository usuarioRepository) {
    this.assinaturaRepository = assinaturaRepository;
    this.usuarioRepository = usuarioRepository;
  }

  /**
   * Lista as assinaturas do usuario do token, paginadas da mais recente para a mais antiga.
   *
   * @param usuarioUuid uuid publico do usuario dono, extraido do token JWT
   * @param page pagina corrente, comecando em 0
   * @param size tamanho da pagina
   * @return pagina com os itens do usuario e o total de assinaturas
   */
  @Transactional(readOnly = true)
  public AssinaturaLista executar(String usuarioUuid, int page, int size) {
    Optional<Usuario> possivelUsuario = usuarioRepository.findByUuid(usuarioUuid);
    if (possivelUsuario.isEmpty()) {
      return new AssinaturaLista(List.of(), page, size, 0);
    }
    Page<Assinatura> pagina =
        assinaturaRepository.findByUsuarioIdOrderByIdDesc(
            possivelUsuario.get().getId(), PageRequest.of(page, size));
    List<AssinaturaResponse> items =
        pagina.getContent().stream()
            .map(assinatura -> toResponse(assinatura, usuarioUuid))
            .toList();
    return new AssinaturaLista(items, page, size, pagina.getTotalElements());
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
