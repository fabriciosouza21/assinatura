package com.globo.assinatura.shared.kafka.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracao do replay da DLQ de consumer.
 *
 * <p>Define o intervalo longo do replay: intervalo entre tentativas de republicacao de uma mesma
 * mensagem que continua falhando no consumer original (bounce). Vale tanto para o backoff do error
 * handler do listener de replay quanto para o adiamento de bounces, com default de 1 hora. O teto
 * de bounces limita quantas republicacoes uma mesma mensagem recebe antes de sair do dreno com
 * erro.
 *
 * @param backoffIntervalMs intervalo entre republicacoes, em milissegundos
 * @param maxBounces teto de republicacoes de uma mesma mensagem em bounce
 */
@ConfigurationProperties("app.kafka.replay")
public record ReplayDlqProperties(
    @DefaultValue("3600000") long backoffIntervalMs, @DefaultValue("3") int maxBounces) {}
