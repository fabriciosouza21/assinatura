package com.globo.pagamento.messaging;

/**
 * Sinaliza que um evento consumido possui campos obrigatorios ausentes ou invalidos.
 *
 * <p>Tratada como nao retentavel pelo {@code DefaultErrorHandler}: vai direto para a DLQ, sem retry
 * e sem chamada ao gateway.
 */
public class EventoInvalidoException extends RuntimeException {

  /**
   * Cria a excecao com a mensagem de validacao.
   *
   * @param mensagem descricao do campo invalido ou ausente
   */
  public EventoInvalidoException(String mensagem) {
    super(mensagem);
  }
}
