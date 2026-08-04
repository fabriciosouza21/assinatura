package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.assinatura.adesao.SolicitarAssinatura;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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
 * Teste de integracao da consulta da assinatura ativa com o cache Redis real.
 *
 * <p>Verifica que a primeira chamada popula a assinatura ativa no Redis com ttl, que a segunda
 * chamada responde o mesmo resultado (acerto de cache), que a ausencia e cacheada como literal
 * {@code null} e que uma solicitacao de assinatura incrementa o contador de versao apos o commit,
 * tornando a versao antiga inalcançavel e repopulando na versao corrente. Usa usuarios e chaves
 * unicos por teste, sem limpeza entre eles.
 */
@SpringBootTest
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=${INTEGRATION_DB_URL:jdbc:postgresql://localhost:5433/assinatura}",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura"
    })
class ConsultarAssinaturaAtivaIntegracaoTest {

  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private ConsultarAssinaturaAtiva consultarAssinaturaAtiva;
  @Autowired private SolicitarAssinatura solicitarAssinatura;
  @Autowired private StringRedisTemplate redisTemplate;

  private static String chaveDe(String usuarioUuid, long versao) {
    return "assinatura:ativa:v" + versao + ":" + usuarioUuid;
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

    Optional<AssinaturaResponse> primeira = consultarAssinaturaAtiva.executar(usuario.getUuid());
    Optional<AssinaturaResponse> segunda = consultarAssinaturaAtiva.executar(usuario.getUuid());

    assertThat(primeira).as("Assinatura ativa consultada").isPresent();
    assertThat(primeira.orElseThrow().status())
        .as("Status da assinatura ativa")
        .isEqualTo(StatusAssinatura.ATIVA);
    assertThat(segunda).as("Segunda chamada responde o mesmo resultado").isEqualTo(primeira);
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 0)))
        .as("Assinatura ativa populada no redis")
        .isNotNull();
    Long ttl = redisTemplate.getExpire(chaveDe(usuario.getUuid(), 0), TimeUnit.SECONDS);
    assertThat(ttl).as("Chave com ttl de seguranca configurado").isBetween(1L, 300L);
  }

  @Test
  @DisplayName("Deve cachear a ausencia quando nao ha assinatura ativa")
  void deveCachearAusenciaQuandoNaoHaAssinaturaAtiva() {
    Usuario usuario = novoUsuario();
    salvarAssinatura(usuario, Plano.BASICO, StatusAssinatura.AGUARDANDO_PAGAMENTO);

    Optional<AssinaturaResponse> primeira = consultarAssinaturaAtiva.executar(usuario.getUuid());
    Optional<AssinaturaResponse> segunda = consultarAssinaturaAtiva.executar(usuario.getUuid());

    assertThat(primeira).as("Primeira chamada sem assinatura ativa").isEmpty();
    assertThat(segunda).as("Segunda chamada responde o mesmo resultado").isEqualTo(primeira);
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 0)))
        .as("Ausencia cacheada como literal nulo")
        .isEqualTo("null");
  }

  @Test
  @DisplayName("Deve invalidar o cache apos solicitar assinatura e repopular na nova versao")
  void deveInvalidarCacheAposSolicitarAssinatura() {
    Usuario usuario = novoUsuario();
    consultarAssinaturaAtiva.executar(usuario.getUuid());
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 0)))
        .as("Ausencia cacheada na versao zero")
        .isEqualTo("null");

    solicitarAssinatura.executar(usuario.getUuid(), Plano.PREMIUM);

    assertThat(redisTemplate.opsForValue().get(contadorDe(usuario.getUuid())))
        .as("Contador compartilhado incrementado apos o commit")
        .isEqualTo("1");
    Optional<AssinaturaResponse> segunda = consultarAssinaturaAtiva.executar(usuario.getUuid());
    assertThat(segunda).as("Assinatura ainda nao esta ativa").isEmpty();
    assertThat(redisTemplate.opsForValue().get(chaveDe(usuario.getUuid(), 1)))
        .as("Ausencia repopulada na versao corrente")
        .isEqualTo("null");
  }
}
