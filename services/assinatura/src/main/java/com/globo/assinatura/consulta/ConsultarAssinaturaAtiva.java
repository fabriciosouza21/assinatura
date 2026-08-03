package com.globo.assinatura.consulta;

import com.globo.assinatura.consulta.api.AssinaturaResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de consulta da assinatura ativa do usuario.
 *
 * <p>Devolve a representacao da assinatura ativa do usuario a partir do cache distribuido
 * (cache-aside). Responsavel apenas por leitura.
 */
@Service
public class ConsultarAssinaturaAtiva {

  private final AssinaturaAtivaCache assinaturaAtivaCache;

  /**
   * Constroi a query com o cache injetado.
   *
   * @param assinaturaAtivaCache cache distribuido da assinatura ativa
   */
  public ConsultarAssinaturaAtiva(AssinaturaAtivaCache assinaturaAtivaCache) {
    this.assinaturaAtivaCache = assinaturaAtivaCache;
  }

  /**
   * Consulta a assinatura ativa do usuario.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @return a representacao da assinatura ativa, ou vazio quando ausente
   */
  @Transactional(readOnly = true)
  public Optional<AssinaturaResponse> executar(String usuarioUuid) {
    return assinaturaAtivaCache.recuperar(usuarioUuid);
  }
}
