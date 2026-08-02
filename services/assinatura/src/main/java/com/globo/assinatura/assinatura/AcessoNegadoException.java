package com.globo.assinatura.assinatura;

/**
 * Sinaliza que o usuario autenticado nao tem permissao sobre a assinatura alvo.
 *
 * <p>Lancada quando a assinatura pertence a outro usuario, ou quando a credencial autenticada nao
 * esta ligada a um usuario de dominio e portanto nao pode assinar. A mensagem nao carrega
 * identificadores para evitar expor dado pessoal em logs.
 */
public class AcessoNegadoException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor identificadores. */
  public AcessoNegadoException() {
    super("Acesso negado a assinatura");
  }
}
