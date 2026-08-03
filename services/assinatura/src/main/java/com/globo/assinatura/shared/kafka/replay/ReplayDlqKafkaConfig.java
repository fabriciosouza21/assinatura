package com.globo.assinatura.shared.kafka.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configuracao da fabrica de containers do listener de replay da DLQ.
 *
 * <p>Cria uma fabrica propria para o {@link ReplayDlqConsumer}, com {@link DefaultErrorHandler} de
 * backoff fixo longo (default 1h, {@code app.kafka.replay.backoff-interval-ms}), distinto do retry
 * curto dos consumers normais. O listener de replay nao publica em outra DLQ: mensagens cujo envio
 * falha sao retentadas no intervalo longo e, esgotadas as tentativas, saem do dreno com erro
 * estruturado ({@code replay_dlq_envio_esgotado}), recuperaveis pelo operador via reset manual do
 * offset do grupo do replay.
 */
@Configuration
@EnableConfigurationProperties(ReplayDlqProperties.class)
public class ReplayDlqKafkaConfig {

  private static final Logger log = LoggerFactory.getLogger(ReplayDlqKafkaConfig.class);

  /**
   * Cria a fabrica de containers do listener de replay com o backoff longo.
   *
   * @param consumerFactory fabrica de consumidores injetada pelo Spring
   * @param properties configuracao do intervalo longo do replay
   * @return fabrica de containers configurada com o error handler de backoff longo
   */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String>
      replayKafkaListenerContainerFactory(
          ConsumerFactory<String, String> consumerFactory, ReplayDlqProperties properties) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(
        new DefaultErrorHandler(recoverer(), backoff(properties.backoffIntervalMs())));
    return factory;
  }

  /**
   * Constroi o backoff fixo longo para o listener de replay.
   *
   * @param intervaloMs intervalo entre tentativas, em milissegundos
   * @return backoff fixo com 3 tentativas no total
   */
  BackOff backoff(long intervaloMs) {
    return new FixedBackOff(intervaloMs, 2L);
  }

  /**
   * Constroi o recoverer que registra a mensagem que saiu do dreno apos esgotar as tentativas de
   * republicacao.
   *
   * @return recoverer com log estruturado de erro
   */
  ConsumerRecordRecoverer recoverer() {
    return (registro, excecao) ->
        log.atError()
            .addKeyValue("event", "replay_dlq_envio_esgotado")
            .addKeyValue("topicoDlq", registro.topic())
            .addKeyValue("chave", registro.key())
            .addKeyValue("offset", registro.offset())
            .setCause(excecao)
            .log("Republicacao esgotou as tentativas; mensagem sai do dreno");
  }
}
