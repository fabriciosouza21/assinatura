package com.globo.pagamento.shared.outbox;

import com.globo.pagamento.shared.outbox.api.OutboxFalhaItem;
import com.globo.pagamento.shared.outbox.api.OutboxFalhaLista;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query de listagem dos eventos da outbox em {@link OutboxStatus#FALHA} para a recuperacao manual
 * assistida.
 *
 * <p>Lista os eventos terminais do mais antigo ao mais recente, com filtro opcional por tipo de
 * evento e idade minima da falha, paginados. E a visao que permite ao operador identificar o que
 * esta preso e decidir o que retomar. Responsavel apenas por leitura.
 *
 * <p>Mantenha em paridade com o {@code ListarFalhasOutbox} de services/pagamento.
 */
@Service
public class ListarFalhasOutbox {

  private final OutboxRepository outboxRepository;

  /**
   * Constroi a query com o repositorio da outbox.
   *
   * @param outboxRepository repositorio da outbox
   */
  public ListarFalhasOutbox(OutboxRepository outboxRepository) {
    this.outboxRepository = outboxRepository;
  }

  /**
   * Lista os eventos em falha, filtrados e paginados.
   *
   * @param tipoEvento tipo de evento para filtro exato, ou {@code null} para listar todos
   * @param idadeMinimaSegundos somente eventos cuja falha ocorreu ha pelo menos essa idade, em
   *     segundos; zero lista todos
   * @param page pagina corrente, comecando em 0
   * @param size tamanho da pagina
   * @return pagina com os eventos em falha e o total ignorando a paginacao
   */
  @Transactional(readOnly = true)
  public OutboxFalhaLista executar(
      String tipoEvento, long idadeMinimaSegundos, int page, int size) {
    Instant limiteFalhouEm = Instant.now().minusSeconds(idadeMinimaSegundos);
    Page<OutboxEvent> pagina =
        outboxRepository.buscarFalhas(tipoEvento, limiteFalhouEm, PageRequest.of(page, size));
    List<OutboxFalhaItem> items = pagina.getContent().stream().map(OutboxFalhaItem::of).toList();
    return new OutboxFalhaLista(items, page, size, pagina.getTotalElements());
  }
}
