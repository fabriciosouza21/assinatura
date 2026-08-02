package com.globo.pagamento.outbox;

import com.globo.pagamento.messaging.RotasEventoTopicoProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracao da outbox do Pagamento Service.
 *
 * <p>Ativa o binding de {@link RotasEventoTopicoProperties} e monta a {@link RetryPolicy} do
 * publisher a partir da configuracao externalizada {@code app.outbox.*}.
 */
@Configuration
@EnableConfigurationProperties(RotasEventoTopicoProperties.class)
public class OutboxConfig {

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
