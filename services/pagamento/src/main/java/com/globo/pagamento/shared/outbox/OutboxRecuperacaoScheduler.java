package com.globo.pagamento.shared.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recupera automaticamente eventos da outbox em {@link OutboxStatus#FALHA} apos o timeout da DLQ,
 * promovendo-os para {@link OutboxStatus#RETENTATIVA_DLQ} e devolvendo-os ao ciclo normal de
 * publicacao do {@link OutboxPublisher}.
 *
 * <p>Limitado a um numero maximo de ciclos de recuperacao por evento: ao esgotar os ciclos, o
 * evento permanece em {@code FALHA} definitivamente, exigindo recuperacao manual.
 *
 * <p>Mantenha em paridade com o {@code OutboxRecuperacaoScheduler} de services/assinatura.
 */
@Component
public class OutboxRecuperacaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxRecuperacaoScheduler.class);

  private final OutboxRepository outboxRepository;
  private final RetryPolicy retryPolicy;
  private final Duration timeout;
  private final int maxCiclos;
  private final int tamanhoLote;

  /**
   * Constroi o scheduler com os beans e a configuracao externalizada injetados.
   *
   * @param outboxRepository repositorio da outbox
   * @param retryPolicy politica de retry usada para espalhar com jitter a elegibilidade dos eventos
   *     recuperados
   * @param timeoutSegundos tempo em falha antes de ser elegivel para recuperacao automatica
   * @param maxCiclos maximo de ciclos de recuperacao automatica por evento
   * @param tamanhoLote maximo de eventos recuperados por ciclo
   */
  public OutboxRecuperacaoScheduler(
      OutboxRepository outboxRepository,
      RetryPolicy retryPolicy,
      @Value("${app.outbox.dlq.timeout-segundos}") long timeoutSegundos,
      @Value("${app.outbox.dlq.max-ciclos}") int maxCiclos,
      @Value("${app.outbox.tamanho-lote}") int tamanhoLote) {
    this.outboxRepository = outboxRepository;
    this.retryPolicy = retryPolicy;
    this.timeout = Duration.ofSeconds(timeoutSegundos);
    this.maxCiclos = maxCiclos;
    this.tamanhoLote = tamanhoLote;
  }

  /**
   * Busca eventos em falha elegiveis para recuperacao e os promove para retentativa, dentro da
   * mesma transacao que reservou as linhas.
   *
   * <p>Um evento cuja recuperacao falhe e isolado: o erro e registrado e o lote segue para os
   * demais. A proxima tentativa de cada evento recuperado recebe o atraso da politica de retry
   * (backoff inicial + jitter), espalhando a elegibilidade dos eventos e evitando thundering herd
   * no {@link OutboxPublisher}.
   */
  @Scheduled(
      initialDelayString = "${app.outbox.dlq.intervalo-ms}",
      fixedDelayString = "${app.outbox.dlq.intervalo-ms}")
  @Transactional
  public void recuperarFalhas() {
    Instant limiteFalhouEm = Instant.now().minus(timeout);
    List<OutboxEvent> eventos =
        outboxRepository.buscarRecuperaveis(limiteFalhouEm, maxCiclos, tamanhoLote);
    log.atDebug()
        .addKeyValue("event", "outbox_dlq_batch_inicio")
        .addKeyValue("tamanhoLote", eventos.size())
        .log("Recuperacao da DLQ iniciada");
    for (OutboxEvent evento : eventos) {
      try {
        evento.recuperarParaRetentativa(Instant.now().plus(retryPolicy.calcularProximoAtraso(1)));
        outboxRepository.save(evento);
        log.atInfo()
            .addKeyValue("event", "outbox_dlq_recuperada")
            .addKeyValue("eventId", evento.getEventId())
            .addKeyValue("aggregateId", evento.getAggregateId())
            .addKeyValue("ciclo", evento.getCiclosRecuperacao())
            .log("Evento da outbox recuperado da DLQ para retentativa");
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "outbox_dlq_falha_isolada")
            .addKeyValue("eventId", evento.getEventId())
            .addKeyValue("errorType", e.getClass().getSimpleName())
            .log("Falha ao recuperar evento da DLQ");
      }
    }
    log.atDebug()
        .addKeyValue("event", "outbox_dlq_batch_fim")
        .addKeyValue("tamanhoLote", eventos.size())
        .log("Recuperacao da DLQ concluida");
  }
}
