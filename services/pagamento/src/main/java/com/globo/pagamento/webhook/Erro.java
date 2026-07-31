package com.globo.pagamento.webhook;

/**
 * Corpo de erro padronizado das respostas do webhook.
 *
 * @param erro codigo do erro
 */
record Erro(String erro) {}
