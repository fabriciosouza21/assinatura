package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaLista;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.shared.cache.CacheVersionado;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AssinaturaListaCacheTest {

  private static final String USUARIO = "550e8400-e29b-41d4-a716-446655440000";

  @Mock private CacheVersionado cacheVersionado;

  private AssinaturaListaCache cache;

  @BeforeEach
  void setUp() {
    cache = new AssinaturaListaCache(cacheVersionado, JsonMapper.builder().build(), 300L);
  }

  private static AssinaturaLista lista() {
    return new AssinaturaLista(
        List.of(
            new AssinaturaResponse(
                "assinatura-uuid",
                USUARIO,
                Plano.PREMIUM,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                StatusAssinatura.ATIVA,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                Instant.parse("2026-08-31T00:00:00Z"),
                true)),
        0,
        20,
        1);
  }

  @Test
  @DisplayName("Deve retornar miss quando a chave da versao corrente nao existe")
  void deveRetornarMissQuandoChaveDaVersaoCorrenteNaoExiste() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());
    when(cacheVersionado.recuperar("assinatura:list:" + USUARIO + ":v0:0:20"))
        .thenReturn(Optional.empty());

    assertThat(cache.recuperar(USUARIO, 0, 20)).as("Chave ausente deve resultar em miss").isEmpty();
  }

  @Test
  @DisplayName("Deve ler a pagina da versao corrente do contador")
  void deveLerPaginaDaVersaoCorrente() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.of("2"));
    when(cacheVersionado.recuperar("assinatura:list:" + USUARIO + ":v2:0:20"))
        .thenReturn(Optional.of(serializar(lista())));

    Optional<AssinaturaLista> resposta = cache.recuperar(USUARIO, 0, 20);

    assertThat(resposta).as("Pagina da versao corrente").isPresent();
    assertThat(resposta.orElseThrow().items()).as("Itens da pagina cacheada").hasSize(1);
    assertThat(resposta.orElseThrow().total()).as("Total da pagina cacheada").isEqualTo(1);
  }

  @Test
  @DisplayName("Deve popular a pagina na chave da versao corrente com ttl")
  void devePopularPaginaNaChaveDaVersaoCorrenteComTtl() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());

    cache.popular(USUARIO, 1, 10, lista());

    ArgumentCaptor<String> chave = ArgumentCaptor.forClass(String.class);
    verify(cacheVersionado).gravar(chave.capture(), any(), eq(Duration.ofSeconds(300)));
    assertThat(chave.getValue())
        .as("Chave versionada por usuario, versao e pagina")
        .isEqualTo("assinatura:list:" + USUARIO + ":v0:1:10");
  }

  @Test
  @DisplayName("Deve tratar json ilegivel no cache como miss")
  void deveTratarJsonIlegivelComoMiss() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());
    when(cacheVersionado.recuperar("assinatura:list:" + USUARIO + ":v0:0:20"))
        .thenReturn(Optional.of("{\"nao\": "));

    assertThat(cache.recuperar(USUARIO, 0, 20))
        .as("JSON corrompido deve degradar para miss")
        .isEmpty();
  }

  @Test
  @DisplayName("Deve ignorar contador ilegivel e usar a versao zero")
  void deveIgnorarContadorIlegivel() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.of("abc"));
    when(cacheVersionado.recuperar("assinatura:list:" + USUARIO + ":v0:0:20"))
        .thenReturn(Optional.empty());

    assertThat(cache.recuperar(USUARIO, 0, 20))
        .as("Contador corrompido deve recair na versao zero")
        .isEmpty();
  }

  private String serializar(AssinaturaLista lista) {
    try {
      return JsonMapper.builder().build().writeValueAsString(lista);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
