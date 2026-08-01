package com.globo.assinatura.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado pelo Pagamento Service quando o status de um pagamento muda.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-kafka.puml}. Consumido pelo
 * Assinatura Service para atualizar o status da assinatura correlacionada via {@code assinaturaId}.
 */
public record PagamentoStatusAtualizado(
    UUID eventId, Instant ocorridoEm, UUID assinaturaId, StatusPagamento status, UUID paymentId) {}
