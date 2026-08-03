package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.globo.assinatura.shared.cache.CacheVersionado;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
