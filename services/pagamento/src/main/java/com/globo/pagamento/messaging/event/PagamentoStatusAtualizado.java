package com.globo.pagamento.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado pelo Pagamento Service quando o status de um pagamento muda.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-kafka.puml}. Consumido pelo
 * Assinatura Service para atualizar o status da assinatura correlacionada via {@code assinaturaId}.
 *
 * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id} do webhook)
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId identificador publico da assinatura (= {@code externalReference} do webhook)
 * @param status status normalizado em APPROVED, REJECTED ou PENDING
 * @param paymentId identificador da cobranca no gateway
 */
public record PagamentoStatusAtualizado(
    UUID eventId, Instant ocorridoEm, UUID assinaturaId, StatusPagamento status, UUID paymentId) {}
