package com.globo.assinatura.consulta.api;

import com.globo.assinatura.consulta.ListarAssinaturas;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller da listagem de assinaturas exposto em {@code /assinaturas}. */
@RestController
@RequestMapping("/assinaturas")
public class ListaAssinaturasController {

  private static final int TAMANHO_MAXIMO = 100;

  private final ListarAssinaturas listarAssinaturas;

  /**
   * Cria o controller com a query de listagem.
   *
   * @param listarAssinaturas query de listagem de assinaturas
   */
  public ListaAssinaturasController(ListarAssinaturas listarAssinaturas) {
    this.listarAssinaturas = listarAssinaturas;
  }

  /**
   * Lista as assinaturas do usuario autenticado, paginadas da mais recente para a mais antiga.
   *
   * @param page pagina corrente, comecando em 0
   * @param size tamanho da pagina, limitado a 100
   * @param principal identidade extraida do token JWT
   * @return a pagina de assinaturas do usuario
   * @throws AcessoNegadoException se o token nao estiver ligado a um usuario de dominio
   */
  @GetMapping
  public AssinaturaLista listar(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal UsuarioAutenticado principal) {
    if (principal.usuarioId() == null || principal.usuarioId().isBlank()) {
      throw new AcessoNegadoException();
    }
    return listarAssinaturas.executar(
        principal.usuarioId(), Math.max(page, 0), Math.min(Math.max(size, 1), TAMANHO_MAXIMO));
  }
}
