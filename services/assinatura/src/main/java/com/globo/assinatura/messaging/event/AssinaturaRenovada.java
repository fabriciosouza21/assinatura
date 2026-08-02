package com.globo.assinatura.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de assinatura renovada, publicado no topico {@code assinatura-renovada}.
 *
 * <p>Sinaliza a retomada do ciclo apos a aprovacao da cobranca da renovacao. O {@code eventId} e a
 * chave de dedup do consumidor downstream, o {@code renovacaoId} referencia a renovacao resolvida e
 * o {@code paymentId} rastreia o pagamento no gateway.
 *
 * @param eventId identificador unico do evento para deduplicacao no consumidor
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId uuid publico da assinatura renovada
 * @param renovacaoId uuid publico da renovacao resolvida
 * @param paymentId identificador publico do pagamento no gateway
 */
public record AssinaturaRenovada(
    UUID eventId, Instant ocorridoEm, UUID assinaturaId, UUID renovacaoId, String paymentId) {}
