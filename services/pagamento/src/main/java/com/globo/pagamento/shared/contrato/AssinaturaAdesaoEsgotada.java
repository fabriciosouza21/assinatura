package com.globo.pagamento.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado quando as tentativas de cobranca de uma adesao se esgotam.
 *
 * <p>Publicado no topico {@code adesao-resultado} quando o teto de falhas tecnicas do gateway e
 * atingido, sem que a cobranca tenha sido criada. Consumido pelo Assinatura Service, que marca a
 * assinatura como {@code PAGAMENTO_FALHOU}.
 *
 * @param eventId identificador unico do evento, gerado ao esgotar por falhas tecnicas
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId identificador publico da assinatura cuja adesao esgotou
 */
public record AssinaturaAdesaoEsgotada(UUID eventId, Instant ocorridoEm, UUID assinaturaId) {}
