package com.globo.assinatura.usuario;

/**
 * Sinaliza tentativa de cadastrar um usuario com email ja existente.
 *
 * <p>Lancada pelo servico de usuario quando o email informado ja esta em uso, caracterizando um
 * conflito de dominio que impede a criacao de um novo registro.
 */
public class EmailJaCadastradoException extends RuntimeException {

  /**
   * Constroi a excecao indicando o email conflitante.
   *
   * @param email email ja cadastrado que gerou o conflito
   */
  public EmailJaCadastradoException(String email) {
    super("E-mail ja cadastrado: " + email);
  }
}
