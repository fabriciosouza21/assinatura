package com.globo.pagamento.gateway;

import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

/**
 * Politica de retry com backoff exponencial para falhas transitorias do gateway de pagamento.
 *
 * <p>Falhas de negocio, respostas {@code 4xx} exceto {@code 408} e {@code 429}, nao sao retentadas
 * e propagam imediatamente.
 */
public final class GatewayRetryPolicy {

  private GatewayRetryPolicy() {}

  /**
   * Cria a politica de retry com backoff exponencial para criacao de cobrancas no gateway.
   *
   * <p>Retenta apenas falhas transitorias: erro de conexao ou timeout ({@link
   * WebClientRequestException}) e respostas {@code 5xx}, {@code 408} ou {@code 429}. Falhas de
   * negocio ({@code 4xx}, exceto {@code 408} e {@code 429}) nao sao retentadas.
   *
   * @param maxAttempts numero maximo de tentativas de retry apos a tentativa inicial
   * @param backoffInicial atraso base da primeira espera, dobrando a cada tentativa seguinte
   * @return politica de retry do Reactor aplicavel a criacao de cobrancas
   */
  public static Retry criar(int maxAttempts, Duration backoffInicial) {
    return Retry.backoff(maxAttempts, backoffInicial)
        .filter(GatewayRetryPolicy::ehFalhaTransitoria);
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
