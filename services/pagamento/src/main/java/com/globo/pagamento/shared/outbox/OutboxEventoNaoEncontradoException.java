package com.globo.pagamento.shared.outbox;

import java.util.UUID;

/**
 * Sinaliza que o evento da outbox informado na retomada manual nao existe.
 *
 * <p>Lancada quando o {@code eventId} recebido no endpoint de retomada nao corresponde a nenhum
 * evento persistido, resultando em {@code 404 Not Found}.
 */
public class OutboxEventoNaoEncontradoException extends RuntimeException {

  /**
   * Constroi a excecao com o identificador do evento ausente.
   *
   * @param eventId identificador do evento nao encontrado
   */
  public OutboxEventoNaoEncontradoException(UUID eventId) {
    super("Evento da outbox nao encontrado: " + eventId);
  }
}
