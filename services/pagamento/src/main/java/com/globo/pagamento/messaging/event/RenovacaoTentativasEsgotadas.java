package com.globo.pagamento.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado quando a ultima tentativa de cobranca de uma renovacao e recusada.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-renovacao.puml}. Publicado no
 * topico {@code renovacao-resultado} com o {@code assinaturaId} como chave e consumido pelo
 * Assinatura Service, que decide a suspensao da assinatura. E emitido apenas por decisao de negocio
 * (recusa do gateway), nunca por falha tecnica.
 *
 * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook)
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId identificador publico da renovacao esgotada
 * @param assinaturaId identificador publico da assinatura nao renovada
 * @param cicloReferencia numero do ciclo da renovacao
 */
public record RenovacaoTentativasEsgotadas(
    UUID eventId, Instant ocorridoEm, UUID renovacaoId, UUID assinaturaId, int cicloReferencia) {}
