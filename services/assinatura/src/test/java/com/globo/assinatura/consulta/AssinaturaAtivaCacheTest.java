package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.shared.cache.CacheVersionado;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
class AssinaturaAtivaCacheTest {

  private static final String USUARIO = "550e8400-e29b-41d4-a716-446655440000";

  @Mock private CacheVersionado cacheVersionado;

  private AssinaturaAtivaCache cache;

  @BeforeEach
  void setUp() {
    cache = new AssinaturaAtivaCache(cacheVersionado, JsonMapper.builder().build(), 300L);
  }

  @Test
  @DisplayName("Deve retornar miss quando a chave da versao corrente nao existe")
  void deveRetornarMissQuandoChaveDaVersaoCorrenteNaoExiste() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());
    when(cacheVersionado.recuperar("assinatura:ativa:v0:" + USUARIO)).thenReturn(Optional.empty());

    assertThat(cache.recuperar(USUARIO)).as("Chave ausente deve resultar em miss").isEmpty();
  }

  @Test
  @DisplayName("Deve ler a assinatura da versao corrente do contador")
  void deveLerAssinaturaDaVersaoCorrente() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.of("2"));
    when(cacheVersionado.recuperar("assinatura:ativa:v2:" + USUARIO))
        .thenReturn(Optional.of(serializar(assinatura())));

    Optional<Optional<AssinaturaResponse>> resposta = cache.recuperar(USUARIO);

    assertThat(resposta).as("Assinatura da versao corrente").contains(Optional.of(assinatura()));
    assertThat(resposta.orElseThrow().orElseThrow())
        .as("Campos da assinatura lida do cache")
        .isEqualTo(assinatura());
  }

  @Test
  @DisplayName("Deve popular a assinatura na chave da versao corrente com ttl")
  void devePopularAssinaturaNaChaveDaVersaoCorrenteComTtl() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());

    cache.popular(USUARIO, Optional.of(assinatura()));

    ArgumentCaptor<String> chaveCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> valorCaptor = ArgumentCaptor.forClass(String.class);
    verify(cacheVersionado)
        .gravar(chaveCaptor.capture(), valorCaptor.capture(), eq(Duration.ofSeconds(300)));
    assertThat(chaveCaptor.getValue())
        .as("Chave da versao corrente")
        .isEqualTo("assinatura:ativa:v0:" + USUARIO);
    assertThat(valorCaptor.getValue()).as("Valor serializado").isEqualTo(serializar(assinatura()));
  }

  @Test
  @DisplayName("Deve popular ausencia como json nulo")
  void devePopularAusenciaComoJsonNulo() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());

    cache.popular(USUARIO, Optional.empty());

    ArgumentCaptor<String> chaveCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> valorCaptor = ArgumentCaptor.forClass(String.class);
    verify(cacheVersionado)
        .gravar(chaveCaptor.capture(), valorCaptor.capture(), eq(Duration.ofSeconds(300)));
    assertThat(chaveCaptor.getValue())
        .as("Chave da versao corrente")
        .isEqualTo("assinatura:ativa:v0:" + USUARIO);
    assertThat(valorCaptor.getValue()).as("Valor serializado da ausencia").isEqualTo("null");
  }

  @Test
  @DisplayName("Deve tratar json ilegivel no cache como miss")
  void deveTratarJsonIlegivelComoMiss() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());
    when(cacheVersionado.recuperar("assinatura:ativa:v0:" + USUARIO))
        .thenReturn(Optional.of("{\"nao\": "));

    assertThat(cache.recuperar(USUARIO)).as("JSON corrompido deve degradar para miss").isEmpty();
  }

  @Test
  @DisplayName("Deve distinguir ausencia cacheada de miss")
  void deveLerAusenciaCacheadaComoVazio() {
    when(cacheVersionado.recuperar("assinatura:list:versao:" + USUARIO))
        .thenReturn(Optional.empty());
    when(cacheVersionado.recuperar("assinatura:ativa:v0:" + USUARIO))
        .thenReturn(Optional.of("null"));

    assertThat(cache.recuperar(USUARIO))
        .as("Ausencia cacheada nao e miss")
        .contains(Optional.empty());
  }

  private static AssinaturaResponse assinatura() {
    return new AssinaturaResponse(
        "assinatura-uuid",
        USUARIO,
        Plano.PREMIUM,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31),
        StatusAssinatura.ATIVA,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 31),
        Instant.parse("2026-08-31T00:00:00Z"),
        true);
  }

  private String serializar(AssinaturaResponse assinatura) {
    try {
      return JsonMapper.builder().build().writeValueAsString(assinatura);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
