package com.globo.pagamento.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao de retry com backoff exponencial para o gateway de pagamento.
 *
 * <p>Define o numero maximo de tentativas, o atraso base, o multiplicador e o jitter usados na
 * politica de retry {@link GatewayRetryPolicy}. O atraso base cresce conforme {@code
 * multiplicador^(tentativa-1)} e recebe um jitter aleatorio fracionario ao redor da base.
 *
 * <p>Exemplo de configuracao em {@code application.yaml}:
 *
 * <pre><code>
 * app:
 *   gateway:
 *     retry:
 *       max-attempts: ${APP_GATEWAY_RETRY_MAX_ATTEMPTS:3}
 *       backoff-inicial-segundos: ${APP_GATEWAY_RETRY_BACKOFF_INICIAL_SEGUNDOS:1}
 *       multiplicador: ${APP_GATEWAY_RETRY_MULTIPLICADOR:2.0}
 *       jitter: ${APP_GATEWAY_RETRY_JITTER:0.5}
 * </code></pre>
 *
 * @param maxAttempts numero maximo de tentativas de retry apos a tentativa inicial
 * @param backoffInicialSegundos atraso base da primeira espera em segundos
 * @param multiplicador fator de crescimento do atraso a cada tentativa, maior ou igual a 1
 * @param jitter amplitude fracionaria do jitter ao redor da base, entre 0 e 1
 */
@ConfigurationProperties("app.gateway.retry")
public record GatewayRetryProperties(
    int maxAttempts, long backoffInicialSegundos, double multiplicador, double jitter) {

  /**
   * Cria as propriedades de retry validando os parametros fail-fast.
   *
   * @throws IllegalArgumentException se {@code maxAttempts} for menor que 1, {@code
   *     backoffInicialSegundos} for menor que 1, {@code multiplicador} for nao finito ou menor que
   *     1, ou {@code jitter} for nao finito ou estiver fora do intervalo {@code [0, 1]}
   */
  public GatewayRetryProperties {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("Max attempts deve ser maior ou igual a 1");
    }
    if (backoffInicialSegundos < 1) {
      throw new IllegalArgumentException("Backoff inicial deve ser maior ou igual a 1 segundo");
    }
    if (!Double.isFinite(multiplicador) || multiplicador < 1) {
      throw new IllegalArgumentException(
          "Multiplicador deve ser um numero finito maior ou igual a 1");
    }
    if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) {
      throw new IllegalArgumentException("Jitter deve ser um numero finito entre 0 e 1");
    }
  }
}
