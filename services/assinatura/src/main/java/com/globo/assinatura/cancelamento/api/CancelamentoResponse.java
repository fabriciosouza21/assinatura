package com.globo.assinatura.cancelamento.api;

import com.globo.assinatura.assinatura.StatusAssinatura;
import java.time.LocalDate;

/**
 * Representa o estado da assinatura apos uma solicitacao de cancelamento.
 *
 * @param id uuid publico da assinatura
 * @param status status atual da assinatura
 * @param renovacaoAutomatica indica se a renovacao automatica esta habilitada
 * @param acessoAte data final do acesso preservado, ou {@code null} quando nao houver ciclo pago
 */
public record CancelamentoResponse(
    String id, StatusAssinatura status, boolean renovacaoAutomatica, LocalDate acessoAte) {}
