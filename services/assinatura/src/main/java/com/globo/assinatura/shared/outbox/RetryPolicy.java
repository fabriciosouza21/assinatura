package com.globo.assinatura.shared.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Politica de retry com backoff exponencial e jitter para reagenda a proxima tentativa de
 * publicacao de um evento da outbox.
 *
 * <p>O atraso base cresce exponencialmente com o numero da tentativa ({@code base * 2^(tentativa -
 * 1)}) e recebe um jitter aleatorio em {@code [0, jitter]} para evitar thundering herd entre
 * instancias do publisher.
 */
public class RetryPolicy {

  private final int maximoTentativas;
  private final Duration backoffInicial;
  private final Duration jitter;

  /**
   * Constroi a politica de retry.
   *
   * @param maximoTentativas numero maximo de tentativas antes de marcar o evento como falha
   * @param backoffInicial atraso base da primeira tentativa, dobrando a cada tentativa seguinte
   * @param jitter amplitude maxima do jitter adicionado ao atraso calculado
   */
  public RetryPolicy(int maximoTentativas, Duration backoffInicial, Duration jitter) {
    this.maximoTentativas = maximoTentativas;
    this.backoffInicial = backoffInicial;
    this.jitter = jitter;
  }

  /**
   * Indica se ainda ha tentativas restantes apos a quantidade informada.
   *
   * @param tentativas numero de tentativas ja realizadas
   * @return {@code true} se o evento ainda pode ser retentado
   */
  public boolean temTentativasRestantes(int tentativas) {
    return tentativas < maximoTentativas;
  }

  /**
   * Calcula o atraso para a proxima tentativa apos {@code tentativas} falhas.
   *
   * @param tentativas numero de tentativas ja realizadas (base 1 para a primeira falha)
   * @return atraso com backoff exponencial e jitter aleatorio
   */
  public Duration calcularProximoAtraso(int tentativas) {
    Duration backoff = backoffInicial.multipliedBy((long) Math.pow(2, tentativas - 1));
    long jitterMillis = ThreadLocalRandom.current().nextLong(0, jitter.toMillis() + 1);
    return backoff.plus(Duration.ofMillis(jitterMillis));
  }
}
