package com.globo.pagamento.cobranca;

/**
 * Sinaliza que a cobranca consultada nao foi encontrada.
 *
 * <p>Lancada pelo servico quando o uuid informado na consulta nao corresponde a nenhuma cobranca. A
 * mensagem nao carrega o uuid para evitar expor identificador em logs.
 */
public class CobrancaNaoEncontradaException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor o uuid informado. */
  public CobrancaNaoEncontradaException() {
    super("Cobranca nao encontrada");
  }
}
