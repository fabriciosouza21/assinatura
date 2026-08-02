package com.globo.pagamento.shared.contrato;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento que informa o agendamento do cancelamento de uma assinatura.
 *
 * @param eventId identificador unico do evento
 * @param ocorridoEm instante em que o cancelamento foi solicitado
 * @param assinaturaId identificador publico da assinatura
 * @param status status da assinatura no momento do agendamento
 * @param fimCiclo data final de acesso da assinatura
 */
public record CancelamentoAgendado(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    StatusAssinatura status,
    LocalDate fimCiclo) {}
