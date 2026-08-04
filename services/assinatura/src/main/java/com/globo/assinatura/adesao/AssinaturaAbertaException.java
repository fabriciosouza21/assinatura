package com.globo.assinatura.adesao;

/**
 * Sinaliza que o usuario ja possui uma assinatura aberta.
 *
 * <p>Lancada pelo servico quando o usuario informado ja tem assinatura em {@link
 * StatusAssinatura#AGUARDANDO_PAGAMENTO}, {@link StatusAssinatura#ATIVA} ou {@link
 * StatusAssinatura#EM_RENOVACAO}, caracterizando um conflito que impede a abertura de uma nova. A
 * mensagem nao carrega identificadores para evitar expor dado pessoal em logs.
 */
public class AssinaturaAbertaException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor identificadores. */
  public AssinaturaAbertaException() {
    super("Usuario ja possui assinatura aberta");
  }
}
