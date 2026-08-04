package com.globo.pagamento.shared.outbox.api;

import java.util.List;

/**
 * Pagina da listagem de eventos da outbox em falha.
 *
 * @param items eventos em falha da pagina, do mais antigo ao mais recente
 * @param page pagina corrente, comecando em 0
 * @param size tamanho da pagina solicitado
 * @param total total de eventos em falha, ignorando a paginacao
 */
public record OutboxFalhaLista(List<OutboxFalhaItem> items, int page, int size, long total) {}
