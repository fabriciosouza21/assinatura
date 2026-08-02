package com.globo.assinatura.shared.contrato;

import com.globo.assinatura.assinatura.StatusAssinatura;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Representa o cancelamento efetivado de uma assinatura.
 *
 * @param eventId identificador unico do evento
 * @param ocorridoEm instante em que o cancelamento foi solicitado
 * @param assinaturaId identificador publico da assinatura
 * @param status status da assinatura apos o cancelamento
 * @param fimCiclo data de fim do ciclo preservado, ou {@code null} quando nao houver ciclo pago
 */
public record AssinaturaCancelada(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    StatusAssinatura status,
    LocalDate fimCiclo) {}
