package com.globo.assinatura.shared.cache;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Primitivas de cache distribuido no Redis com degradacao silenciosa.
 *
 * <p>A leitura e a gravacao seguem o cache-aside puro: falha do Redis vira miss silencioso ou
 * gravacao descartada, nunca excecao para o chamador. A invalidacao de listagens e feita por
 * contador versionado incrementado apos o commit da transacao corrente (invalidate-on-write),
 * tornando a versao antiga inalcançavel sem varredura de chaves.
 */
@Service
public class CacheVersionado {

  private static final Logger log = LoggerFactory.getLogger(CacheVersionado.class);

  private final StringRedisTemplate redisTemplate;

  /**
   * Constroi o cache com o template do Redis.
   *
   * @param redisTemplate template de acesso ao Redis
   */
  public CacheVersionado(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /**
   * Recupera o valor armazenado na chave.
   *
   * @param chave chave do valor
   * @return o valor, ou vazio se a chave nao existir ou se o Redis estiver indisponivel
   */
  public Optional<String> recuperar(String chave) {
    try {
      return Optional.ofNullable(redisTemplate.opsForValue().get(chave));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "cache_redis_leitura_indisponivel")
          .addKeyValue("errorType", e.getClass().getSimpleName())
          .log("Cache Redis indisponivel na leitura, degradando para miss");
      return Optional.empty();
    }
  }

  /**
   * Grava o valor na chave com o tempo de vida informado.
   *
   * @param chave chave do valor
   * @param valor valor a armazenar
   * @param ttl tempo de vida do valor
   */
  public void gravar(String chave, String valor, Duration ttl) {
    try {
      redisTemplate.opsForValue().set(chave, valor, ttl);
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "cache_redis_gravacao_indisponivel")
          .addKeyValue("errorType", e.getClass().getSimpleName())
          .log("Cache Redis indisponivel na gravacao, valor descartado");
    }
  }

  /**
   * Registra o incremento do contador da chave para apos o commit da transacao corrente.
   *
   * <p>Sem transacao ativa no ponto de chamada o incremento seria executado antes do commit,
   * invalidando o cache com dado ainda nao confirmado; por isso a exigencia.
   *
   * @param chaveContador chave do contador de versao a incrementar
   * @throws IllegalStateException se nao houver transacao ativa no ponto de chamada
   */
  public void invalidarAposCommit(String chaveContador) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              redisTemplate.opsForValue().increment(chaveContador);
            } catch (RuntimeException e) {
              log.atWarn()
                  .addKeyValue("event", "cache_redis_invalidacao_indisponivel")
                  .addKeyValue("errorType", e.getClass().getSimpleName())
                  .log("Cache Redis indisponivel na invalidacao, ttl cobre a defasagem");
            }
          }
        });
  }
}
