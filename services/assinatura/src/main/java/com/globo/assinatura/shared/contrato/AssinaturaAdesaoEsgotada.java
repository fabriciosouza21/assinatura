package com.globo.assinatura.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de esgotamento das tentativas de cobranca de uma adesao.
 *
 * <p>Consumido do topico {@code adesao-resultado}. Espelha o contrato emitido pelo Pagamento
 * Service.
 *
 * @param eventId identificador unico do evento, usado na deduplicacao
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId identificador publico da assinatura cuja adesao esgotou
 */
public record AssinaturaAdesaoEsgotada(UUID eventId, Instant ocorridoEm, UUID assinaturaId) {}
