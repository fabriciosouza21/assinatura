package com.globo.assinatura.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de renovacao aprovada, consumido do topico {@code renovacao-resultado}.
 *
 * <p>Espelha o contrato produzido pelo Pagamento Service: mesmos campos e tipos, sem modulo
 * compartilhado entre servicos. O {@code eventId} e a chave de idempotencia do consumidor, o {@code
 * assinaturaId} referencia a assinatura dona e o {@code cicloReferencia} e o ordinal do ciclo
 * renovado.
 *
 * @param eventId identificador unico do evento para deduplicacao no consumidor
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId uuid publico da renovacao aprovada
 * @param assinaturaId uuid publico da assinatura dona da renovacao
 * @param paymentId identificador publico do pagamento no gateway
 * @param cicloReferencia ordinal do ciclo da renovacao aprovada
 */
public record PagamentoRenovacaoAprovado(
    UUID eventId,
    Instant ocorridoEm,
    UUID renovacaoId,
    UUID assinaturaId,
    String paymentId,
    int cicloReferencia) {}
