package com.globo.assinatura.usuario;

/**
 * Sinaliza tentativa de cadastrar um usuario com email ja existente.
 *
 * <p>Lancada pelo servico de usuario quando o email informado ja esta em uso, caracterizando um
 * conflito de dominio que impede a criacao de um novo registro. A mensagem nao carrega o email
 * conflitante para evitar expor dado pessoal (LGPD) em logs.
 */
public class EmailJaCadastradoException extends RuntimeException {

  /** Constroi a excecao com mensagem fixa, sem expor o email conflitante. */
  public EmailJaCadastradoException() {
    super("E-mail ja cadastrado");
  }
}
