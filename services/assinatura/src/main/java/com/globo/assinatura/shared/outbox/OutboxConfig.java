package com.globo.assinatura.shared.outbox;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configura a politica de retry usada pelo publisher da outbox. */
@Configuration
public class OutboxConfig {

  /**
   * Constroi a politica de retry a partir da configuracao externalizada.
   *
   * @param maximoTentativas maximo de tentativas antes da falha
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
