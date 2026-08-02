package com.globo.pagamento.outbox;

import java.time.Duration;

/**
 * Politica de retry com backoff exponencial e jitter para reagenda a proxima tentativa de
 * publicacao de um evento da outbox.
 */
public class RetryPolicy {

  private final int maximoTentativas;

  /**
   * Constroi a politica de retry.
   *
   * @param maximoTentativas numero maximo de tentativas antes de marcar o evento como falha
   * @param backoffInicial atraso base da primeira tentativa, dobrando a cada tentativa seguinte
   * @param jitter amplitude maxima do jitter adicionado ao atraso calculado
   */
  public RetryPolicy(int maximoTentativas, Duration backoffInicial, Duration jitter) {
    this.maximoTentativas = maximoTentativas;
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
}
