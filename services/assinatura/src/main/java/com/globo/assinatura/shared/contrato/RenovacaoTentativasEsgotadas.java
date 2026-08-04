package com.globo.assinatura.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de tentativas de renovacao esgotadas, consumido do topico {@code renovacao-resultado}.
 *
 * <p>Espelha o contrato produzido pelo Pagamento Service: mesmos campos e tipos, sem modulo
 * compartilhado entre servicos. O {@code eventId} e a chave de idempotencia do consumidor, o {@code
 * assinaturaId} referencia a assinatura dona e o {@code cicloReferencia} e o ordinal do ciclo cuja
 * cobranca teve as tentativas esgotadas.
 *
 * @param eventId identificador unico do evento para deduplicacao no consumidor
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId uuid publico da renovacao com tentativas esgotadas
 * @param assinaturaId uuid publico da assinatura dona da renovacao
 * @param cicloReferencia ordinal do ciclo da renovacao esgotada
 */
public record RenovacaoTentativasEsgotadas(
    UUID eventId, Instant ocorridoEm, UUID renovacaoId, UUID assinaturaId, int cicloReferencia) {}
