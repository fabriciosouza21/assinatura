package com.globo.pagamento.renovacao;

/**
 * Sinaliza que o pagamento da renovacao consultada nao foi encontrado.
 *
 * <p>Lancada pelo servico quando o uuid informado na consulta nao corresponde a nenhum pagamento de
 * renovacao. A mensagem nao carrega o uuid para evitar expor identificador em logs.
 */
public class RenovacaoNaoEncontradaException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor o uuid informado. */
  public RenovacaoNaoEncontradaException() {
    super("Renovacao nao encontrada");
  }
}
