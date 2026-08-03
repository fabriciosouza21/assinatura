package com.globo.pagamento.gateway;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Politica de retry com backoff exponencial para falhas transitorias do gateway de pagamento.
 *
 * <p>O atraso base cresce conforme {@code multiplicador^(tentativa-1)} e recebe um jitter aleatorio
 * fracionario ao redor da base. Falhas de negocio, respostas {@code 4xx} exceto {@code 408} e
 * {@code 429}, nao sao retentadas e propagam imediatamente.
 */
public final class GatewayRetryPolicy {

  private static final Logger log = LoggerFactory.getLogger(GatewayRetryPolicy.class);

  private GatewayRetryPolicy() {}

  /**
   * Cria a politica de retry com backoff exponencial para criacao de cobrancas no gateway.
   *
   * <p>Usa multiplicador {@code 2.0} e jitter {@code 0.5}, os mesmos valores do backoff padrao do
   * Reactor. Retenta apenas falhas transitorias: erro de conexao ou timeout ({@link
   * WebClientRequestException}) e respostas {@code 5xx}, {@code 408} ou {@code 429}. Falhas de
   * negocio ({@code 4xx}, exceto {@code 408} e {@code 429}) nao sao retentadas.
   *
   * @param maxAttempts numero maximo de tentativas de retry apos a tentativa inicial
   * @param backoffInicial atraso base da primeira espera, dobrando a cada tentativa seguinte
   * @return politica de retry do Reactor aplicavel a criacao de cobrancas
   */
  public static Retry criar(int maxAttempts, Duration backoffInicial) {
    return criar(maxAttempts, backoffInicial, 2.0, 0.5);
  }

  /**
   * Cria a politica de retry com backoff exponencial, multiplicador e jitter configuraveis.
   *
   * <p>O atraso de cada retry e {@code backoffInicial * multiplicador^(tentativa-1)} com um jitter
   * aleatorio fracionario ao redor da base. Retenta apenas falhas transitorias: erro de conexao ou
   * timeout ({@link WebClientRequestException}) e respostas {@code 5xx}, {@code 408} ou {@code
   * 429}. Falhas de negocio ({@code 4xx}, exceto {@code 408} e {@code 429}) nao sao retentadas.
   *
   * @param maxAttempts numero maximo de tentativas de retry apos a tentativa inicial
   * @param backoffInicial atraso base da primeira espera
   * @param multiplicador fator de crescimento do atraso a cada tentativa, maior ou igual a 1
   * @param jitter amplitude fracionaria do jitter ao redor da base, entre 0 e 1
   * @return politica de retry do Reactor aplicavel a criacao de cobrancas
   * @throws IllegalArgumentException se {@code multiplicador} for nao finito ou menor que 1, ou
   *     {@code jitter} for nao finito ou estiver fora do intervalo {@code [0, 1]}
   */
  public static Retry criar(
      int maxAttempts, Duration backoffInicial, double multiplicador, double jitter) {
    validarParametros(multiplicador, jitter);
    return Retry.from(
        companion ->
            companion.flatMap(
                sinal -> {
                  if (!ehFalhaTransitoria(sinal.failure()) || sinal.totalRetries() >= maxAttempts) {
                    return Mono.error(sinal.failure());
                  }
                  long tentativa = sinal.totalRetries() + 1;
                  Duration backoff =
                      calcularBackoff(
                          tentativa,
                          backoffInicial,
                          multiplicador,
                          jitter,
                          ThreadLocalRandom.current());
                  log.atDebug()
                      .addKeyValue("event", "gateway_retry_tentativa")
                      .addKeyValue("tentativa", tentativa)
                      .addKeyValue("backoffMs", backoff.toMillis())
                      .log("Retentando chamada ao gateway");
                  return Mono.delay(backoff);
                }));
  }

  /**
   * Calcula o atraso de backoff exponencial para uma tentativa.
   *
   * <p>O atraso base e {@code backoffInicial * multiplicador^(tentativa-1)} e recebe um jitter
   * aleatorio fracionario ao redor da base: {@code base * (1 + jitter * (2 * aleatorio - 1))}.
   *
   * @param tentativa numero da tentativa, base 1 para a primeira
   * @param backoffInicial atraso base da primeira tentativa
   * @param multiplicador fator de crescimento do atraso a cada tentativa
   * @param jitter amplitude fracionaria do jitter, entre 0 e 1
   * @param aleatorio fonte de aleatoriedade do jitter
   * @return atraso calculado com backoff exponencial e jitter
   * @throws IllegalArgumentException se {@code tentativa} for menor que 1, {@code multiplicador}
   *     menor que 1 ou {@code jitter} fora do intervalo {@code [0, 1]}
   */
  static Duration calcularBackoff(
      long tentativa,
      Duration backoffInicial,
      double multiplicador,
      double jitter,
      Random aleatorio) {
    if (tentativa < 1) {
      throw new IllegalArgumentException("Tentativa deve ser maior ou igual a 1");
    }
    validarParametros(multiplicador, jitter);
    double baseMillis = backoffInicial.toMillis() * Math.pow(multiplicador, tentativa - 1);
    double fatorJitter = 1 + jitter * (2 * aleatorio.nextDouble() - 1);
    return Duration.ofMillis((long) (baseMillis * fatorJitter));
  }

  private static void validarParametros(double multiplicador, double jitter) {
    if (!Double.isFinite(multiplicador) || multiplicador < 1) {
      throw new IllegalArgumentException(
          "Multiplicador deve ser um numero finito maior ou igual a 1");
    }
    if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) {
      throw new IllegalArgumentException("Jitter deve ser um numero finito entre 0 e 1");
    }
  }

  private static boolean ehFalhaTransitoria(Throwable excecao) {
    if (excecao instanceof WebClientRequestException) {
      return true;
    }
    if (excecao instanceof WebClientResponseException resposta) {
      int status = resposta.getStatusCode().value();
      return status >= 500 || status == 408 || status == 429;
    }
    return false;
  }
}
