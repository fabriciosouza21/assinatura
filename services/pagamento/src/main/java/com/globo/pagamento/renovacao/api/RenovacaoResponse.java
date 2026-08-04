package com.globo.pagamento.renovacao.api;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.StatusTentativa;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representacao do pagamento de renovacao mais recente de uma assinatura, retornada na consulta.
 *
 * @param assinaturaId uuid publico da assinatura renovada
 * @param renovacaoId uuid publico da renovacao
 * @param cicloReferencia numero do ciclo renovado
 * @param plano plano contratado
 * @param valor valor da renovacao em reais
 * @param numeroTentativa numero da tentativa de cobranca corrente
 * @param statusTentativa status da tentativa corrente
 * @param paymentId identificador da cobranca no gateway de pagamento, ou {@code null} enquanto o
 *     gateway nao e chamado
 * @param proximaTentativaEm instante da proxima tentativa, ou {@code null} enquanto nao agendada
 */
public record RenovacaoResponse(
    String assinaturaId,
    String renovacaoId,
    int cicloReferencia,
    Plano plano,
    BigDecimal valor,
    int numeroTentativa,
    StatusTentativa statusTentativa,
    String paymentId,
    Instant proximaTentativaEm) {}
