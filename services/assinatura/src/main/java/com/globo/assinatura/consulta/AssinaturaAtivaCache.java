package com.globo.assinatura.consulta;

import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.shared.cache.CacheVersionado;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside da assinatura ativa do usuario, chaveado por uuid publico.
 *
 * <p>A chave de dados segue {@code assinatura:ativa:v{n}:{uuid}}, com {@code n} lido do contador de
 * versao compartilhado com a listagem ({@code assinatura:list:versao:{uuid}}); a invalidacao apos o
 * commit dos fluxos de escrita ja incrementa esse contador. Em miss devolve vazio para a camada de
 * consulta buscar no banco; ausencia cacheada devolve presente com interno vazio, sem consultar o
 * banco. Falha do cache degrada para miss, nunca para erro da consulta.
 */
@Component
public class AssinaturaAtivaCache {

  private static final Logger log = LoggerFactory.getLogger(AssinaturaAtivaCache.class);

  private static final String PREFIXO = "assinatura:ativa";

  private final CacheVersionado cache;
  private final JsonMapper jsonMapper;
  private final Duration ttl;

  /**
   * Constroi o cache com as primitivas do Redis, o serializador JSON e o ttl configurado.
   *
   * @param cache primitivas de cache distribuido
   * @param jsonMapper serializador JSON da assinatura ativa
   * @param ttlSegundos tempo de vida da assinatura ativa em segundos
   */
  public AssinaturaAtivaCache(
      CacheVersionado cache,
      JsonMapper jsonMapper,
      @Value("${app.cache.assinatura-ativa-ttl}") long ttlSegundos) {
    this.cache = cache;
    this.jsonMapper = jsonMapper;
    this.ttl = Duration.ofSeconds(ttlSegundos);
  }

  /**
   * Recupera a assinatura ativa cacheada do usuario.
   *
   * <p>O par opcional externo distingue acerto de miss: presente significa chave lida, com interno
   * vazio quando a ausencia esta cacheada e interno preenchido em acerto de assinatura.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @return par opcional em acerto, com interno vazio quando a ausencia esta cacheada; externo
   *     vazio em miss ou em falha do cache
   */
  public Optional<Optional<AssinaturaResponse>> recuperar(String usuarioUuid) {
    String chave = chaveDe(usuarioUuid);
    Optional<String> possivelJson = cache.recuperar(chave);
    if (possivelJson.isEmpty()) {
      log.atInfo()
          .addKeyValue("event", "assinatura_ativa_cache_miss")
          .addKeyValue("usuarioId", usuarioUuid)
          .log("Assinatura ativa ausente no cache");
      return Optional.empty();
    }
    try {
      AssinaturaResponse assinatura =
          jsonMapper.readValue(possivelJson.get(), AssinaturaResponse.class);
      log.atInfo()
          .addKeyValue("event", "assinatura_ativa_cache_hit")
          .addKeyValue("usuarioId", usuarioUuid)
          .log("Assinatura ativa lida do cache");
      return Optional.of(Optional.ofNullable(assinatura));
    } catch (JacksonException e) {
      log.atWarn()
          .addKeyValue("event", "assinatura_ativa_cache_json_invalido")
          .addKeyValue("usuarioId", usuarioUuid)
          .log("JSON da assinatura ativa cacheada ilegivel, tratando como miss");
      return Optional.empty();
    }
  }

  /**
   * Grava a assinatura ativa do usuario no cache distribuido.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @param assinatura representacao da assinatura ativa a armazenar, ou vazio para cachear a
   *     ausencia
   */
  public void popular(String usuarioUuid, Optional<AssinaturaResponse> assinatura) {
    try {
      cache.gravar(
          chaveDe(usuarioUuid), jsonMapper.writeValueAsString(assinatura.orElse(null)), ttl);
    } catch (JacksonException e) {
      log.atWarn()
          .addKeyValue("event", "assinatura_ativa_cache_populacao_falhou")
          .addKeyValue("usuarioId", usuarioUuid)
          .log("Falha ao serializar assinatura ativa para o cache");
    }
  }

  private String chaveDe(String usuarioUuid) {
    return PREFIXO + ":v" + versaoAtual(usuarioUuid) + ":" + usuarioUuid;
  }

  private long versaoAtual(String usuarioUuid) {
    String possivelVersao = cache.recuperar("assinatura:list:versao:" + usuarioUuid).orElse("0");
    try {
      return Long.parseLong(possivelVersao);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
