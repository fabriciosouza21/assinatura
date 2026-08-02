package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.assinatura.adesao.SolicitarAssinatura;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaLista;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao da listagem com o cache Redis real.
 *
 * <p>Verifica que a primeira chamada popula a pagina no Redis com ttl, que a segunda leitura
 * responde o mesmo resultado (acerto de cache), que a ordenacao e da mais recente para a mais
 * antiga e que uma solicitacao de assinatura incrementa o contador de versao apos o commit,
 * tornando a pagina antiga inalcançavel. Usa usuarios e chaves unicos por teste, sem limpeza entre
 * eles.
 */
@SpringBootTest
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=${INTEGRATION_DB_URL:jdbc:postgresql://localhost:5433/assinatura}",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura"
    })
class ListarAssinaturasIntegracaoTest {

  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private ListarAssinaturas listarAssinaturas;
  @Autowired private SolicitarAssinatura solicitarAssinatura;
  @Autowired private StringRedisTemplate redisTemplate;

  private static String chaveDe(String usuarioUuid, long versao, int page, int size) {
    return "assinatura:list:" + usuarioUuid + ":v" + versao + ":" + page + ":" + size;
  }

  private static String contadorDe(String usuarioUuid) {
    return "assinatura:list:versao:" + usuarioUuid;
  }

  private Usuario novoUsuario() {
    return usuarioRepository.save(
        new Usuario("Fulano", "fulano-" + UUID.randomUUID() + "@example.com"));
  }

  private Assinatura salvarAssinatura(Usuario usuario, Plano plano, StatusAssinatura status) {
    Assinatura assinatura = new Assinatura(usuario.getId(), plano);
    if (status == StatusAssinatura.ATIVA) {
      assinatura.ativar(
          LocalDate.of(2026, 7, 1),
          LocalDate.of(2026, 7, 31),
          Instant.parse("2026-07-31T00:00:00Z"));
    } else if (status == StatusAssinatura.CANCELADA) {
      assinatura.solicitarCancelamento();
    }
    return assinaturaRepository.save(assinatura);
  }

  @Test
  @DisplayName("Deve popular o cache na primeira chamada e responder o mesmo resultado na segunda")
  void devePopularCacheNaPrimeiraChamada() {
    Usuario usuario = novoUsuario();
    salvarAssinatura(usuario, Plano.PREMIUM, StatusAssinatura.ATIVA);
    salvarAssinatura(usuario, Plano.BASICO, StatusAssinatura.CANCELADA);

    AssinaturaLista primeira = listarAssinaturas.executar(usuario.getUuid(), 0, 20);
    AssinaturaLista segunda = listarAssinaturas.executar(usuario.getUuid(), 0, 20);

    assertThat(primeira.total()).as("Total de assinaturas do usuario").isEqualTo(2);
    assertThat(segunda).as("Segunda chamada responde o mesmo resultado").isEqualTo(primeira);
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 0, 0, 20)))
        .as("Pagina populada no redis")
        .isNotNull();
    Long ttl = redisTemplate.getExpire(chaveDe(usuario.getUuid(), 0, 0, 20), TimeUnit.SECONDS);
    assertThat(ttl).as("Pagina com ttl de seguranca configurado").isBetween(1L, 300L);
  }

  @Test
  @DisplayName("Deve listar da mais recente para a mais antiga")
  void deveListarDaMaisRecenteParaMaisAntiga() {
    Usuario usuario = novoUsuario();
    Assinatura primeira = new Assinatura(usuario.getId(), Plano.BASICO);
    primeira.solicitarCancelamento();
    primeira = assinaturaRepository.save(primeira);
    Assinatura segunda = assinaturaRepository.save(new Assinatura(usuario.getId(), Plano.PREMIUM));

    AssinaturaLista pagina = listarAssinaturas.executar(usuario.getUuid(), 0, 20);

    assertThat(pagina.items())
        .as("Itens da mais recente para a mais antiga")
        .extracting(AssinaturaResponse::id)
        .containsExactly(segunda.getUuid(), primeira.getUuid());
  }

  @Test
  @DisplayName("Deve invalidar o cache apos solicitar assinatura e repopular na nova versao")
  void deveInvalidarCacheAposSolicitarAssinatura() {
    Usuario usuario = novoUsuario();
    listarAssinaturas.executar(usuario.getUuid(), 0, 20);
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 0, 0, 20)))
        .as("Pagina vazia populada na versao zero")
        .isNotNull();

    solicitarAssinatura.executar(usuario.getUuid(), Plano.PREMIUM);

    assertThat(redisTemplate.opsForValue().get(contadorDe(usuario.getUuid())))
        .as("Contador de versao incrementado apos o commit da solicitacao")
        .isEqualTo("1");
    AssinaturaLista pagina = listarAssinaturas.executar(usuario.getUuid(), 0, 20);
    assertThat(pagina.total()).as("Listagem reflete a nova assinatura").isEqualTo(1);
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 1, 0, 20)))
        .as("Pagina repopulada na versao corrente")
        .isNotNull();
  }
}
