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
 */
@Component
public class OutboxRecuperacaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxRecuperacaoScheduler.class);

  private final OutboxRepository outboxRepository;
  private final Duration timeout;
  private final int maxCiclos;
  private final int tamanhoLote;

  /**
   * Constroi o scheduler com os beans e a configuracao externalizada injetados.
   *
   * @param outboxRepository repositorio da outbox
   * @param timeoutSegundos tempo em falha antes de ser elegivel para recuperacao automatica
   * @param maxCiclos maximo de ciclos de recuperacao automatica por evento
   * @param tamanhoLote maximo de eventos recuperados por ciclo
   */
  public OutboxRecuperacaoScheduler(
      OutboxRepository outboxRepository,
      @Value("${app.outbox.dlq.timeout-segundos}") long timeoutSegundos,
      @Value("${app.outbox.dlq.max-ciclos}") int maxCiclos,
      @Value("${app.outbox.tamanho-lote}") int tamanhoLote) {
    this.outboxRepository = outboxRepository;
    this.timeout = Duration.ofSeconds(timeoutSegundos);
    this.maxCiclos = maxCiclos;
    this.tamanhoLote = tamanhoLote;
  }

  /**
   * Busca eventos em falha elegiveis para recuperacao e os promove para retentativa, dentro da
   * mesma transacao que reservou as linhas.
   */
  @Scheduled(fixedDelayString = "${app.outbox.dlq.intervalo-ms}")
  @Transactional
  public void recuperarFalhas() {
    Instant limiteFalhouEm = Instant.now().minus(timeout);
    List<OutboxEvent> eventos =
        outboxRepository.buscarRecuperaveis(limiteFalhouEm, maxCiclos, tamanhoLote);
    for (OutboxEvent evento : eventos) {
      evento.recuperarParaRetentativa(Instant.now());
      outboxRepository.save(evento);
      log.atInfo()
          .addKeyValue("event", "outbox_dlq_recuperada")
          .addKeyValue("eventId", evento.getEventId())
          .addKeyValue("aggregateId", evento.getAggregateId())
          .addKeyValue("ciclo", evento.getCiclosRecuperacao())
          .log("Evento da outbox recuperado da DLQ para retentativa");
    }
  }
}
