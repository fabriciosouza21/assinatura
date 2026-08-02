package com.globo.assinatura.consulta;

import com.globo.assinatura.consulta.api.AssinaturaLista;
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
 * Cache-aside da listagem de assinaturas, chaveado por usuario, versao, pagina e tamanho.
 *
 * <p>As chaves de dados seguem {@code assinatura:list:{usuarioUuid}:v{versao}:{pagina}:{tamanho}} e
 * o contador de versao {@code assinatura:list:versao:{usuarioUuid}}. A invalidacao incrementa o
 * contador: a pagina antiga vira inalcançavel e expira no ttl, sem varredura de chaves. Falha do
 * Redis degrada para miss (consulta ao banco), nunca para erro da listagem.
 */
@Component
public class AssinaturaListaCache {

  private static final Logger log = LoggerFactory.getLogger(AssinaturaListaCache.class);

  private static final String PREFIXO = "assinatura:list";

  private final CacheVersionado cache;
  private final JsonMapper jsonMapper;
  private final Duration ttl;

  /**
   * Constroi o cache com as primitivas do Redis, o serializador JSON e o ttl configurado.
   *
   * @param cache primitivas de cache distribuido
   * @param jsonMapper serializador JSON da pagina
   * @param ttlSegundos tempo de vida das paginas em segundos
   */
  public AssinaturaListaCache(
      CacheVersionado cache,
      JsonMapper jsonMapper,
      @Value("${app.cache.assinatura-lista-ttl}") long ttlSegundos) {
    this.cache = cache;
    this.jsonMapper = jsonMapper;
    this.ttl = Duration.ofSeconds(ttlSegundos);
  }

  /**
   * Recupera a pagina cacheada do usuario na versao corrente.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @param page pagina corrente
   * @param size tamanho da pagina
   * @return a pagina, ou vazio em miss ou em falha do Redis
   */
  public Optional<AssinaturaLista> recuperar(String usuarioUuid, int page, int size) {
    String chave = chaveDe(usuarioUuid, page, size);
    Optional<String> possivelJson = cache.recuperar(chave);
    if (possivelJson.isEmpty()) {
      log.atInfo()
          .addKeyValue("event", "assinatura_lista_cache_miss")
          .addKeyValue("usuarioId", usuarioUuid)
          .addKeyValue("pagina", page)
          .log("Pagina de assinaturas ausente no cache");
      return Optional.empty();
    }
    try {
      AssinaturaLista lista = jsonMapper.readValue(possivelJson.get(), AssinaturaLista.class);
      log.atInfo()
          .addKeyValue("event", "assinatura_lista_cache_hit")
          .addKeyValue("usuarioId", usuarioUuid)
          .addKeyValue("pagina", page)
          .log("Pagina de assinaturas lida do cache");
      return Optional.of(lista);
    } catch (JacksonException e) {
      log.atWarn()
          .addKeyValue("event", "assinatura_lista_cache_json_invalido")
          .addKeyValue("usuarioId", usuarioUuid)
          .addKeyValue("pagina", page)
          .log("JSON da pagina cacheada ilegivel, tratando como miss");
      return Optional.empty();
    }
  }

  /**
   * Grava a pagina do usuario na chave da versao corrente com o ttl configurado.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @param page pagina corrente
   * @param size tamanho da pagina
   * @param lista pagina a armazenar
   */
  public void popular(String usuarioUuid, int page, int size, AssinaturaLista lista) {
    try {
      cache.gravar(chaveDe(usuarioUuid, page, size), jsonMapper.writeValueAsString(lista), ttl);
    } catch (JacksonException e) {
      log.atWarn()
          .addKeyValue("event", "assinatura_lista_cache_populacao_falhou")
          .addKeyValue("usuarioId", usuarioUuid)
          .addKeyValue("pagina", page)
          .log("Falha ao serializar a pagina para o cache");
    }
  }

  private String chaveDe(String usuarioUuid, int page, int size) {
    return PREFIXO + ":" + usuarioUuid + ":v" + versaoAtual(usuarioUuid) + ":" + page + ":" + size;
  }

  private long versaoAtual(String usuarioUuid) {
    String possivelVersao = cache.recuperar(PREFIXO + ":versao:" + usuarioUuid).orElse("0");
    try {
      return Long.parseLong(possivelVersao);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
