package com.globo.assinatura.shared.contrato;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Representa o cancelamento agendado de uma assinatura ao fim do ciclo vigente.
 *
 * @param eventId identificador unico do evento
 * @param ocorridoEm instante em que o cancelamento foi solicitado
 * @param assinaturaId identificador publico da assinatura
 * @param status status da assinatura apos a solicitacao
 * @param fimCiclo data de fim do ciclo preservado
 */
public record CancelamentoAgendado(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    StatusAssinatura status,
    LocalDate fimCiclo) {}
