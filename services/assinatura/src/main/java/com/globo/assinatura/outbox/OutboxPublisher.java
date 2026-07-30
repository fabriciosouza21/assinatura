package com.globo.assinatura.outbox;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drena a outbox publicando eventos pendentes no Kafka e transita o estado de cada evento conforme
 * o resultado do envio.
 *
 * <p>Em sucesso, marca o evento como {@link OutboxStatus#PUBLICADO}. Em falha, incrementa as
 * tentativas e reagenda com backoff + jitter; ao esgotar as tentativas, marca como {@link
 * OutboxStatus#FALHA} (DLQ persistida). A entrega e pelo menos uma vez: o consumidor deduplica por
 * {@code eventId}.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final RetryPolicy retryPolicy;
  private final String topico;
  private final int tamanhoLote;

  /**
   * Constroi o publisher com os beans e a configuracao externalizada injetados.
   *
   * @param outboxRepository repositorio da outbox
   * @param kafkaTemplate template de publicacao no Kafka
   * @param retryPolicy politica de retry
   * @param topico topico Kafka de destino
   * @param tamanhoLote maximo de eventos publicados por ciclo
   */
  public OutboxPublisher(
      OutboxRepository outboxRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      RetryPolicy retryPolicy,
      @Value("${app.kafka.topico-assinatura-solicitada}") String topico,
      @Value("${app.outbox.tamanho-lote}") int tamanhoLote) {
    this.outboxRepository = outboxRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.retryPolicy = retryPolicy;
    this.topico = topico;
    this.tamanhoLote = tamanhoLote;
  }

  /** Seleciona eventos pendentes prontos para envio e publica cada um no Kafka. */
  @Scheduled(fixedDelayString = "${app.outbox.intervalo-ms}")
  @Transactional
  public void publicarPendentes() {
    List<OutboxEvent> eventos = outboxRepository.buscarPublicaveis(Instant.now(), tamanhoLote);
    for (OutboxEvent evento : eventos) {
      publicar(evento);
    }
  }

  private void publicar(OutboxEvent evento) {
    try {
      kafkaTemplate
          .send(topico, evento.getAggregateId().toString(), evento.getPayload())
          .whenComplete((resultado, erro) -> tratarResultado(evento, erro));
    } catch (RuntimeException e) {
      tratarFalha(evento, e);
      outboxRepository.save(evento);
    }
  }

  private void tratarResultado(OutboxEvent evento, Throwable erro) {
    if (erro == null) {
      evento.marcarPublicado(Instant.now());
    } else {
      tratarFalha(evento, erro);
    }
    outboxRepository.save(evento);
  }

  private void tratarFalha(OutboxEvent evento, Throwable erro) {
    log.warn(
        "Falha ao publicar evento {} (tentativa {}): {}",
        evento.getEventId(),
        evento.getTentativas() + 1,
        erro.getMessage());
    int tentativas = evento.getTentativas() + 1;
    if (retryPolicy.temTentativasRestantes(tentativas)) {
      evento.registrarFalha(
          mensagem(erro), Instant.now().plus(retryPolicy.calcularProximoAtraso(tentativas)));
    } else {
      evento.marcarFalha(mensagem(erro), Instant.now());
    }
  }

  private static String mensagem(Throwable erro) {
    return erro.getMessage() != null ? erro.getMessage() : erro.getClass().getSimpleName();
  }
}
