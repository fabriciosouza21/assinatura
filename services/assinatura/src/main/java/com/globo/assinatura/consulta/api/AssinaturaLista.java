package com.globo.assinatura.consulta.api;

import java.util.List;

/**
 * Pagina da listagem de assinaturas do usuario autenticado.
 *
 * @param items assinaturas da pagina, da mais recente para a mais antiga
 * @param page pagina corrente, comecando em 0
 * @param size tamanho da pagina solicitado
 * @param total total de assinaturas do usuario, ignorando a paginacao
 */
public record AssinaturaLista(List<AssinaturaResponse> items, int page, int size, long total) {}
