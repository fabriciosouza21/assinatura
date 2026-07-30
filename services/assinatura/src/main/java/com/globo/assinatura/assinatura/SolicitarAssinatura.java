package com.globo.assinatura.assinatura;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de solicitacao de assinatura.
 *
 * <p>Cria uma assinatura para o usuario e o plano informados, aplicando a regra "um usuario, uma
 * assinatura aberta". Responsavel apenas por escrita.
 */
@Service
public class SolicitarAssinatura {

  private static final List<StatusAssinatura> STATUS_ABERTOS =
      List.of(StatusAssinatura.AGUARDANDO_PAGAMENTO, StatusAssinatura.ATIVA);

  private final UsuarioRepository usuarioRepository;
  private final AssinaturaRepository assinaturaRepository;

  /**
   * Constroi o command com os repositorios injetados.
   *
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   */
  public SolicitarAssinatura(
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
   * @throws UsuarioNaoEncontradoException se o uuid nao corresponder a um usuario cadastrado
   * @throws AssinaturaAbertaException se o usuario ja possuir assinatura aberta
   */
  @Transactional
  public Assinatura executar(String usuarioUuid, Plano plano) {
    Usuario usuario =
        usuarioRepository.findByUuid(usuarioUuid).orElseThrow(UsuarioNaoEncontradoException::new);
    if (assinaturaRepository.existsByUsuarioIdAndStatusIn(usuario.getId(), STATUS_ABERTOS)) {
      throw new AssinaturaAbertaException();
    }
    Assinatura assinatura = new Assinatura(usuario.getId(), plano);
    try {
      return assinaturaRepository.save(assinatura);
    } catch (DataIntegrityViolationException e) {
      throw new AssinaturaAbertaException();
    }
  }
}
