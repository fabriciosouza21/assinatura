package com.globo.pagamento.messaging.event;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de renovacao solicitada, consumido do topico {@code renovacao-solicitada}.
 *
 * <p>Espelha o contrato {@code docs/contratos/contrato-eventos-renovacao.puml}: campos e tipos
 * exatos, sem modulo compartilhado entre servicos.
 *
 * @param eventId identificador unico do evento, usado na deduplicacao
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId identificador publico da renovacao solicitada (chave de idempotencia)
 * @param assinaturaId identificador publico da assinatura renovada
 * @param plano plano contratado
 * @param valor valor mensal em reais
 * @param cicloReferencia numero do ciclo da renovacao
 */
public record RenovacaoSolicitada(
    UUID eventId,
    Instant ocorridoEm,
    UUID renovacaoId,
    UUID assinaturaId,
    Plano plano,
    BigDecimal valor,
    int cicloReferencia) {}
