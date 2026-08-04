package com.globo.assinatura.consulta.api;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import java.time.Instant;
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
 * @param inicioCiclo inicio do ciclo de renovacao corrente, ou {@code null} antes da primeira
 *     ativacao
 * @param fimCiclo fim do ciclo de renovacao corrente, ou {@code null} antes da primeira ativacao
 * @param proximaRenovacaoEm instante da proxima renovacao, ou {@code null} antes da primeira
 *     ativacao
 * @param renovacaoAutomatica indica se a renovacao automatica esta habilitada
 */
public record AssinaturaResponse(
    String id,
    String usuarioId,
    Plano plano,
    LocalDate dataInicio,
    LocalDate dataExpiracao,
    StatusAssinatura status,
    LocalDate inicioCiclo,
    LocalDate fimCiclo,
    Instant proximaRenovacaoEm,
    boolean renovacaoAutomatica) {

  /**
   * Converte a assinatura persistida na representacao publica de consulta.
   *
   * @param assinatura assinatura persistida
   * @param usuarioUuid uuid publico do usuario dono
   * @return a representacao publica da assinatura
   */
  public static AssinaturaResponse of(Assinatura assinatura, String usuarioUuid) {
    return new AssinaturaResponse(
        assinatura.getUuid(),
        usuarioUuid,
        assinatura.getPlano(),
        assinatura.getDataInicio(),
        assinatura.getDataExpiracao(),
        assinatura.getStatus(),
        assinatura.getInicioCiclo(),
        assinatura.getFimCiclo(),
        assinatura.getProximaRenovacaoEm(),
        assinatura.isRenovacaoAutomatica());
  }
}
