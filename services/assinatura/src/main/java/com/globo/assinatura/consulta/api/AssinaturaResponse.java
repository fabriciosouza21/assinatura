package com.globo.assinatura.consulta.api;

import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import java.time.LocalDate;

/**
 * Representacao completa de uma assinatura retornada na consulta.
 *
 * @param id uuid publico da assinatura
 * @param usuarioId uuid publico do usuario dono
 * @param plano plano contratado
 * @param dataInicio data de inicio da vigencia, ou {@code null} enquanto aguarda pagamento
 * @param dataExpiracao data de expiracao da vigencia, ou {@code null} enquanto aguarda pagamento
 * @param status status do ciclo de vida da assinatura
 */
public record AssinaturaResponse(
    String id,
    String usuarioId,
    Plano plano,
    LocalDate dataInicio,
    LocalDate dataExpiracao,
    StatusAssinatura status) {}
