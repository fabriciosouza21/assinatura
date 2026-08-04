package com.globo.pagamento.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado quando as tentativas de cobranca de uma renovacao se esgotam.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-renovacao.puml}. Publicado no
 * topico {@code renovacao-resultado} com o {@code assinaturaId} como chave e consumido pelo
 * Assinatura Service, que decide a suspensao da assinatura. E emitido pela recusa da ultima
 * tentativa (decisao de negocio do gateway) ou pelo teto de falhas tecnicas consecutivas atingido.
 *
 * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook, ou gerado ao
 *     esgotar por falhas tecnicas)
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId identificador publico da renovacao esgotada
 * @param assinaturaId identificador publico da assinatura nao renovada
 * @param cicloReferencia numero do ciclo da renovacao
 */
public record RenovacaoTentativasEsgotadas(
    UUID eventId, Instant ocorridoEm, UUID renovacaoId, UUID assinaturaId, int cicloReferencia) {}
