package com.globo.assinatura.shared.outbox;

import java.util.UUID;

/**
 * Sinaliza que o evento da outbox informado na retomada manual nao esta em falha.
 *
 * <p>Lancada quando o evento existe mas seu status atual nao e {@link OutboxStatus#FALHA}, o que
 * indicaria recuperacao duplicada sob chamadas concorrentes, resultando em {@code 409 Conflict} sem
 * alterar o evento.
 */
public class OutboxEventoNaoRetomavelException extends RuntimeException {

  /**
   * Constroi a excecao com o identificador do evento e o status observado.
   *
   * @param eventId identificador do evento nao retomavel
   * @param status status atual do evento
   */
  public OutboxEventoNaoRetomavelException(UUID eventId, OutboxStatus status) {
    super("Evento da outbox nao retomavel: " + eventId + " (" + status + ")");
  }
}
