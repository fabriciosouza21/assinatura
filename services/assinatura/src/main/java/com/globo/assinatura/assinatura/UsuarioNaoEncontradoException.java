package com.globo.assinatura.assinatura;

/**
 * Sinaliza que o usuario informado na solicitacao de assinatura nao foi encontrado.
 *
 * <p>Lancada pelo servico quando o uuid informado nao corresponde a nenhum usuario cadastrado. A
 * mensagem nao carrega o uuid para evitar expor identificador em logs.
 */
public class UsuarioNaoEncontradoException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor o uuid informado. */
  public UsuarioNaoEncontradoException() {
    super("Usuario nao encontrado");
  }
}
