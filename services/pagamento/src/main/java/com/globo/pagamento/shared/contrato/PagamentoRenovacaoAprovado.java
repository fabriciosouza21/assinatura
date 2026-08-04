package com.globo.pagamento.shared.contrato;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado quando a cobranca de uma renovacao e aprovada pelo gateway.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-renovacao.puml}. Publicado no
 * topico {@code renovacao-resultado} com o {@code assinaturaId} como chave e consumido pelo
 * Assinatura Service para rolar o ciclo da assinatura para frente. A aprovacao encerra as
 * tentativas da renovacao.
 *
 * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook)
 * @param ocorridoEm instante em que o evento ocorreu
 * @param renovacaoId identificador publico da renovacao aprovada
 * @param assinaturaId identificador publico da assinatura renovada
 * @param paymentId identificador da cobranca no gateway
 * @param cicloReferencia numero do ciclo da renovacao
 */
public record PagamentoRenovacaoAprovado(
    UUID eventId,
    Instant ocorridoEm,
    UUID renovacaoId,
    UUID assinaturaId,
    String paymentId,
    int cicloReferencia) {}
