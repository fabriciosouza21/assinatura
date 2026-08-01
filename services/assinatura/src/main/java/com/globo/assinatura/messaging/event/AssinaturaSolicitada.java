package com.globo.assinatura.messaging.event;

import com.globo.assinatura.assinatura.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio publicado quando uma assinatura e solicitada.
 *
 * <p>Contrato travado em {@code docs/contratos/contrato-eventos-kafka.puml}. Carrega os campos
 * necessarios ao Pagamento Service para criar a cobranca: quem solicitou, qual plano e qual valor.
 * O {@code valor} e derivado de {@link Plano#valor()} ao montar o evento, nao persistido na
 * assinatura.
 */
public record AssinaturaSolicitada(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId,
    UUID usuarioId,
    Plano plano,
    BigDecimal valor) {

  /**
   * Constroi o evento derivando o {@code valor} do plano informado.
   *
   * @param eventId identificador unico do evento para deduplicacao no consumidor
   * @param ocorridoEm instante em que o evento ocorreu
   * @param assinaturaId uuid publico da assinatura solicitada
   * @param usuarioId uuid publico do usuario solicitante
   * @param plano plano contratado
   */
  public AssinaturaSolicitada(
      UUID eventId, Instant ocorridoEm, UUID assinaturaId, UUID usuarioId, Plano plano) {
    this(eventId, ocorridoEm, assinaturaId, usuarioId, plano, plano.valor());
  }
}
