package com.globo.pagamento.shared.outbox.api;

import com.globo.pagamento.shared.outbox.ListarFalhasOutbox;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.RetomarEventoOutbox;
import com.globo.pagamento.shared.seguranca.AcessoNegadoException;
import com.globo.pagamento.shared.seguranca.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller da recuperacao manual assistida da outbox exposto em {@code /outbox/falhas}.
 *
 * <p>Permite ao operador listar os eventos em {@link OutboxStatus#FALHA} e retomar um evento
 * especifico pelo {@code eventId}, ambos restritos a administradores.
 */
@RestController
@RequestMapping("/outbox/falhas")
public class OutboxFalhaController {

  private static final int TAMANHO_MAXIMO = 100;
  private static final int PAGINA_MAXIMA = 10_000;
  private static final long FALHOU_HA_MAXIMO_SEGUNDOS = 31_536_000L;

  private final ListarFalhasOutbox listarFalhasOutbox;
  private final RetomarEventoOutbox retomarEventoOutbox;

  /**
   * Constroi o controller com a query de listagem e o command de retomada.
   *
   * @param listarFalhasOutbox query de listagem de eventos em falha
   * @param retomarEventoOutbox command de retomada de evento em falha
   */
  public OutboxFalhaController(
      ListarFalhasOutbox listarFalhasOutbox, RetomarEventoOutbox retomarEventoOutbox) {
    this.listarFalhasOutbox = listarFalhasOutbox;
    this.retomarEventoOutbox = retomarEventoOutbox;
  }

  /**
   * Lista os eventos da outbox em falha, filtrados e paginados.
   *
   * @param tipoEvento tipo de evento para filtro exato, opcional
   * @param falhouHaSegundos somente eventos cuja falha ocorreu ha pelo menos esse numero de
   *     segundos, padrao zero
   * @param page pagina corrente, comecando em 0
   * @param size tamanho da pagina, limitado a 100
   * @param principal identidade extraida do token JWT
   * @return a pagina de eventos em falha
   * @throws AcessoNegadoException se o token nao for de um administrador
   */
  @GetMapping
  public OutboxFalhaLista listar(
      @RequestParam(required = false) String tipoEvento,
      @RequestParam(defaultValue = "0") long falhouHaSegundos,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal UsuarioAutenticado principal) {
    exigirAdmin(principal);
    return listarFalhasOutbox.executar(
        tipoEvento,
        Math.min(Math.max(falhouHaSegundos, 0), FALHOU_HA_MAXIMO_SEGUNDOS),
        Math.min(Math.max(page, 0), PAGINA_MAXIMA),
        Math.min(Math.max(size, 1), TAMANHO_MAXIMO));
  }

  /**
   * Retoma um evento em falha, devolvendo-o ao ciclo de publicacao.
   *
   * @param eventId identificador do evento a retomar
   * @param principal identidade extraida do token JWT
   * @return o evento retomado com o status corrente
   * @throws AcessoNegadoException se o token nao for de um administrador
   */
  @PostMapping("/{eventId}/retomada")
  public OutboxFalhaRetomadaResponse retomar(
      @PathVariable UUID eventId, @AuthenticationPrincipal UsuarioAutenticado principal) {
    exigirAdmin(principal);
    OutboxEvent evento = retomarEventoOutbox.executar(eventId, principal.email());
    return new OutboxFalhaRetomadaResponse(evento.getEventId(), evento.getStatus());
  }

  private static void exigirAdmin(UsuarioAutenticado principal) {
    if (principal == null || !principal.isAdmin()) {
      throw new AcessoNegadoException();
    }
  }
}
