package com.globo.assinatura.messaging.event;

import com.globo.assinatura.assinatura.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de renovacao solicitada, publicado no topico {@code renovacao-solicitada}.
 *
 * <p>Espelha o contrato consumido pelo Pagamento Service: mesmos campos e tipos, sem modulo
 * compartilhado entre servicos. O {@code renovacaoId} e a chave de idempotencia do consumidor e o
 * {@code cicloReferencia} e o ordinal do ciclo dentro da assinatura.
 *
 * @param eventId identificador unico do evento para deduplicacao no consumidor
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId uuid publico da renovacao solicitada
 * @param assinaturaId uuid publico da assinatura renovada
 * @param plano plano contratado
 * @param valor valor mensal em reais
 * @param cicloReferencia ordinal do ciclo da renovacao
 */
public record RenovacaoSolicitada(
    UUID eventId,
    Instant ocorridoEm,
    UUID renovacaoId,
    UUID assinaturaId,
    Plano plano,
    BigDecimal valor,
    int cicloReferencia) {}
