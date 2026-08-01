package com.globo.assinatura.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de assinatura suspensa, publicado no topico {@code assinatura-suspensa}.
 *
 * <p>Sinaliza que o acesso foi suspenso apos o esgotamento das tentativas de cobranca da renovacao.
 * O {@code eventId} e a chave de deduplicacao do consumidor downstream e o {@code renovacaoId}
 * referencia a renovacao cujas tentativas se esgotaram.
 *
 * @param eventId identificador unico do evento para deduplicacao no consumidor
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId uuid publico da assinatura suspensa
 * @param renovacaoId uuid publico da renovacao cujas tentativas se esgotaram
 */
public record AssinaturaSuspensa(
    UUID eventId, Instant ocorridoEm, UUID assinaturaId, UUID renovacaoId) {}
