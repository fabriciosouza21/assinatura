package com.globo.assinatura.assinatura;

/**
 * Sinaliza que a assinatura consultada nao foi encontrada.
 *
 * <p>Lancada pelo servico quando o uuid informado na consulta nao corresponde a nenhuma assinatura.
 * A mensagem nao carrega o uuid para evitar expor identificador em logs.
 */
public class AssinaturaNaoEncontradaException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor o uuid informado. */
  public AssinaturaNaoEncontradaException() {
    super("Assinatura nao encontrada");
  }
}
