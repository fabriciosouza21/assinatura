package com.globo.pagamento.shared.contrato;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento que informa o cancelamento efetivado de uma assinatura.
 *
 * <p>E emitido para cancelamentos imediatos e para o opt-out efetivado pelo scheduler no fim do
 * ciclo.
 *
 * @param eventId identificador unico do evento
 * @param ocorridoEm instante em que o cancelamento ocorreu
 * @param assinaturaId identificador publico da assinatura
 * @param status status da assinatura no momento do cancelamento
 * @param fimCiclo data final de acesso da assinatura, quando houver
 */
public record AssinaturaCancelada(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    StatusAssinatura status,
    LocalDate fimCiclo) {}
