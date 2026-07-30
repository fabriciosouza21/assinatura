package com.globo.assinatura.messaging;

import com.globo.assinatura.outbox.RetryPolicy;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuracao de mensageria do Assinatura Service.
 *
 * <p>Declara o topico {@code assinatura-solicitada} via KafkaAdmin (nao depende de auto-create),
 * ativa o agendamento do publisher da outbox e expoe a politica de retry.
 */
@Configuration
@EnableScheduling
public class MessagingConfig {

  /**
   * Declara o topico de eventos {@code AssinaturaSolicitada}.
   *
   * @param nome nome do topico, externalizado por configuracao
   * @return topico Kafka a ser criado pelo KafkaAdmin
   */
  @Bean
  public NewTopic topicoAssinaturaSolicitada(
      @Value("${app.kafka.topico-assinatura-solicitada}") String nome) {
    return new NewTopic(nome, 1, (short) 1);
  }

  /**
   * Constroi a politica de retry do publisher a partir da configuracao externalizada.
   *
   * @param maximoTentativas maximo de tentativas antes da DLQ
   * @param backoffInicialSegundos atraso base em segundos, dobrando a cada tentativa
   * @param jitterMillis amplitude do jitter em milissegundos
   * @return politica de retry configurada
   */
  @Bean
  public RetryPolicy retryPolicy(
      @Value("${app.outbox.max-tentativas}") int maximoTentativas,
      @Value("${app.outbox.backoff-inicial-segundos}") long backoffInicialSegundos,
      @Value("${app.outbox.jitter-millis}") long jitterMillis) {
    return new RetryPolicy(
        maximoTentativas,
        Duration.ofSeconds(backoffInicialSegundos),
        Duration.ofMillis(jitterMillis));
  }
}
