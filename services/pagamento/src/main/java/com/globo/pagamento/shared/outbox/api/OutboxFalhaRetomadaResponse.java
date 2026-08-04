package com.globo.pagamento.shared.outbox.api;

import com.globo.pagamento.shared.outbox.OutboxStatus;
import java.util.UUID;

/**
 * Resposta da retomada manual de um evento da outbox.
 *
 * @param eventId identificador do evento retomado
 * @param status status corrente do evento apos a retomada
 */
public record OutboxFalhaRetomadaResponse(UUID eventId, OutboxStatus status) {}
