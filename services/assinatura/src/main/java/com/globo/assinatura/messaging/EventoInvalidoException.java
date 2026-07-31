package com.globo.assinatura.messaging;

/**
 * Sinaliza que um evento {@code PagamentoStatusAtualizado} possui campos obrigatorios ausentes.
 *
 * <p>Tratada como nao retentavel pelo {@code DefaultErrorHandler}: vai direto para a DLQ, sem retry
 * e sem processamento de dominio, pois payload invalido sempre sera invalido.
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
