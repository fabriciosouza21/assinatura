package com.globo.assinatura.shared.kafka.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Republica no topico original as mensagens retidas nos topicos {@code *-dlq}.
 *
 * <p>Desligado por padrao ({@code app.kafka.replay.auto-startup=false}): o listener so consome os
 * topicos DLQ quando o operador habilita o replay por configuracao. Ao consumir, le o topico
 * original do header {@code kafka_dlt-original-topic} anexado pelo {@code
 * DeadLetterPublishingRecoverer} e republica a payload bruta com a mesma chave, preservando a
 * semantica dos guards de idempotencia do consumer original. O topico original so e aceito quando
 * corresponde a DLQ de origem sem o sufixo {@code -dlq}; qualquer outro destino e descartado com
 * WARN. Mensagens sem topico original legivel tambem sao descartadas com WARN.
 *
 * <p>Uma mensagem que volta a falhar no consumer original (bounce) carrega o marcador {@code
 * app-replay-republish} e so e re-republicada apos o intervalo configurado: dentro do intervalo o
 * listener adia via {@code nack}, sem gerar ciclo rapido de replay. Cada republicacao incrementa o
 * contador {@code app-replay-attempts}; ao atingir {@code app.kafka.replay.max-bounces}, a mensagem
 * sai do dreno com ERROR para nao travar o replay indefinidamente.
 */
@Component
public class ReplayDlqConsumer {

  /** Nome do header que carrega o epoch ms da ultima republicacao da mensagem. */
  public static final String HEADER_REPUBLICACAO = "app-replay-republish";

  /** Nome do header que carrega o numero de republicacoes ja tentadas da mensagem. */
  public static final String HEADER_TENTATIVAS = "app-replay-attempts";

  private static final String SUFIXO_DLQ = "-dlq";

  private static final Logger log = LoggerFactory.getLogger(ReplayDlqConsumer.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ReplayDlqProperties properties;

  /**
   * Constroi o listener de replay com o template de publicacao e a configuracao injetados.
   *
   * @param kafkaTemplate template usado para republicar no topico original
   * @param properties configuracao do intervalo longo e do teto de bounces do replay
   */
  public ReplayDlqConsumer(
      KafkaTemplate<String, String> kafkaTemplate, ReplayDlqProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
  }

  /**
   * Republica uma mensagem da DLQ no topico original.
   *
   * @param registro mensagem consumida do topico DLQ, com a chave e a payload brutas
   * @param acknowledgment confirmacao manual do offset da mensagem
   */
  @KafkaListener(
      topics = {
        "${app.kafka.topico-pagamento-status-atualizado-dlq}",
        "${app.kafka.topico-renovacao-resultado-dlq}"
      },
      groupId = "assinatura-replay-dlq",
      containerFactory = "replayKafkaListenerContainerFactory",
      autoStartup = "${app.kafka.replay.auto-startup:false}",
      ackMode = "MANUAL")
  public void republicar(ConsumerRecord<String, String> registro, Acknowledgment acknowledgment) {
    String topicoOriginal = lerTopicoOriginal(registro);
    if (topicoOriginal == null) {
      log.atWarn()
          .addKeyValue("event", "replay_dlq_topico_origem_ausente")
          .addKeyValue("topicoDlq", registro.topic())
          .addKeyValue("chave", registro.key())
          .addKeyValue("offset", registro.offset())
          .log("Mensagem da DLQ sem topico original descartada");
      acknowledgment.acknowledge();
      return;
    }
    if (!topicoOriginal.equals(topicoSemSufixoDlq(registro.topic()))) {
      log.atWarn()
          .addKeyValue("event", "replay_dlq_topico_origem_invalido")
          .addKeyValue("topicoDlq", registro.topic())
          .addKeyValue("chave", registro.key())
          .addKeyValue("offset", registro.offset())
          .log("Mensagem da DLQ com topico original invalido descartada");
      acknowledgment.acknowledge();
      return;
    }
    int tentativas = lerTentativas(registro);
    if (tentativas >= properties.maxBounces()) {
      log.atError()
          .addKeyValue("event", "replay_dlq_bounces_esgotados")
          .addKeyValue("topicoDlq", registro.topic())
          .addKeyValue("chave", registro.key())
          .addKeyValue("offset", registro.offset())
          .addKeyValue("maxBounces", properties.maxBounces())
          .log("Teto de republicacoes do bounce atingido; mensagem sai do dreno");
      acknowledgment.acknowledge();
      return;
    }
    Long ultimaRepublicacao = lerUltimaRepublicacao(registro);
    if (ultimaRepublicacao != null) {
      long espera = calcularEspera(ultimaRepublicacao, properties.backoffIntervalMs());
      if (espera > 0) {
        log.atDebug()
            .addKeyValue("event", "replay_dlq_bounce_adiado")
            .addKeyValue("topicoOriginal", topicoOriginal)
            .addKeyValue("esperaMs", espera)
            .log("Bounce adiado ate o fim do intervalo do replay");
        acknowledgment.nack(Duration.ofMillis(espera));
        return;
      }
    }
    republicarNoTopicoOriginal(topicoOriginal, registro, tentativas + 1);
    log.atInfo()
        .addKeyValue("event", "replay_dlq_mensagem_republicada")
        .addKeyValue("topicoOriginal", topicoOriginal)
        .log("Mensagem da DLQ republicada no topico original");
    acknowledgment.acknowledge();
  }

  private void republicarNoTopicoOriginal(
      String topicoOriginal, ConsumerRecord<String, String> registro, int tentativas) {
    try {
      kafkaTemplate
          .send(
              new ProducerRecord<>(
                  topicoOriginal,
                  null,
                  registro.key(),
                  registro.value(),
                  marcadorDeRepublicacao(tentativas)))
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("replay interrompido", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("falha ao republicar mensagem da dlq", e.getCause());
    }
  }

  private static String lerTopicoOriginal(ConsumerRecord<String, String> registro) {
    String topico = valorHeader(registro, KafkaHeaders.DLT_ORIGINAL_TOPIC);
    return topico == null || topico.isBlank() ? null : topico;
  }

  private static String topicoSemSufixoDlq(String topico) {
    return topico.endsWith(SUFIXO_DLQ)
        ? topico.substring(0, topico.length() - SUFIXO_DLQ.length())
        : null;
  }

  private static Long lerUltimaRepublicacao(ConsumerRecord<String, String> registro) {
    String valor = valorHeader(registro, HEADER_REPUBLICACAO);
    if (valor == null) {
      return null;
    }
    try {
      return Long.parseLong(valor);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int lerTentativas(ConsumerRecord<String, String> registro) {
    String valor = valorHeader(registro, HEADER_TENTATIVAS);
    if (valor == null) {
      return 0;
    }
    try {
      return Integer.parseInt(valor);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String valorHeader(ConsumerRecord<String, String> registro, String nome) {
    Header header = registro.headers().lastHeader(nome);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static long calcularEspera(long ultimaRepublicacao, long intervaloMs) {
    long agora = System.currentTimeMillis();
    if (ultimaRepublicacao < agora - intervaloMs || ultimaRepublicacao > agora) {
      return 0;
    }
    return intervaloMs - (agora - ultimaRepublicacao);
  }

  private static List<Header> marcadorDeRepublicacao(int tentativas) {
    return List.of(
        new RecordHeader(
            HEADER_REPUBLICACAO,
            Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)),
        new RecordHeader(
            HEADER_TENTATIVAS, Integer.toString(tentativas).getBytes(StandardCharsets.UTF_8)));
  }
}
