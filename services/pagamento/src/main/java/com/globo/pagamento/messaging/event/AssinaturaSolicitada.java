package com.globo.pagamento.messaging.event;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de assinatura solicitada, consumido do topico {@code assinatura-solicitada}.
 *
 * <p>Espelha o contrato {@code docs/contratos/contrato-eventos-kafka.puml}: campos e tipos exatos,
 * sem modulo compartilhado entre servicos.
 *
 * @param eventId identificador unico do evento, usado na deduplicacao
 * @param ocorridoEm instante em que o evento ocorreu
 * @param assinaturaId identificador publico da assinatura solicitada
 * @param usuarioId identificador publico do usuario solicitante
 * @param plano plano contratado
 * @param valor valor mensal em reais
 */
public record AssinaturaSolicitada(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    UUID usuarioId,
    Plano plano,
    BigDecimal valor) {}
