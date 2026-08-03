package com.globo.pagamento.shared.outbox;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retoma manualmente um evento da outbox em {@link OutboxStatus#FALHA}, devolvendo-o ao ciclo
 * normal de publicacao do {@link OutboxPublisher}.
 *
 * <p>E o desfecho operacional quando a recuperacao automatica do {@link OutboxRecuperacaoScheduler}
 * esgota os ciclos: o operador identifica o evento pela listagem e o promove para {@link
 * OutboxStatus#RETENTATIVA_DLQ} pela mesma transicao de dominio do scheduler, sob lock pessimista
 * para serializar chamadas concorrentes. A acao fica registrada em log de auditoria com o
 * identificador do administrador.
 *
 * <p>Mantenha em paridade com o {@code RetomarEventoOutbox} de services/assinatura.
 */
@Service
public class RetomarEventoOutbox {

  private static final Logger log = LoggerFactory.getLogger(RetomarEventoOutbox.class);

  private final OutboxRepository outboxRepository;
  private final RetryPolicy retryPolicy;

  /**
   * Constroi o command com o repositorio e a politica de retry injetados.
   *
   * @param outboxRepository repositorio da outbox
   * @param retryPolicy politica de retry usada para agendar a proxima tentativa com backoff e
   *     jitter, no mesmo espalhamento do scheduler
   */
  public RetomarEventoOutbox(OutboxRepository outboxRepository, RetryPolicy retryPolicy) {
    this.outboxRepository = outboxRepository;
    this.retryPolicy = retryPolicy;
  }

  /**
   * Retoma um evento em falha pela primeira tentativa de um novo ciclo de publicacao.
   *
   * @param eventId identificador do evento a retomar
   * @param adminId identificador do administrador que dispara a acao, registrado em auditoria
   * @return o evento retomado, ja em {@link OutboxStatus#RETENTATIVA_DLQ}
   * @throws OutboxEventoNaoEncontradoException se nao existir evento com o identificador informado
   * @throws OutboxEventoNaoRetomavelException se o evento nao estiver em {@link OutboxStatus#FALHA}
   */
  @Transactional
  public OutboxEvent executar(UUID eventId, String adminId) {
    OutboxEvent evento =
        outboxRepository
            .buscarPorIdComLock(eventId)
            .orElseThrow(() -> new OutboxEventoNaoEncontradoException(eventId));
    if (evento.getStatus() != OutboxStatus.FALHA) {
      throw new OutboxEventoNaoRetomavelException(eventId, evento.getStatus());
    }
    evento.recuperarParaRetentativa(Instant.now().plus(retryPolicy.calcularProximoAtraso(1)));
    outboxRepository.save(evento);
    log.atInfo()
        .addKeyValue("event", "outbox_falha_retomada_manual")
        .addKeyValue("eventId", eventId)
        .addKeyValue("eventType", evento.getEventType())
        .addKeyValue("adminId", adminId)
        .log("Evento da outbox retomado manualmente para retentativa");
    return evento;
  }
}
