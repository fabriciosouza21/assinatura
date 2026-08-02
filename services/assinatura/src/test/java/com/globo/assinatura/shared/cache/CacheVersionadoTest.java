package com.globo.assinatura.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

@ExtendWith(MockitoExtension.class)
class CacheVersionadoTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> operacoes;

  @InjectMocks private CacheVersionado cache;

  @AfterEach
  void limparSincronizacao() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("Deve recuperar o valor armazenado na chave")
  void deveRecuperarValorArmazenadoNaChave() {
    when(redisTemplate.opsForValue()).thenReturn(operacoes);
    when(operacoes.get("assinatura:list:versao:usuario-uuid")).thenReturn("3");

    Optional<String> valor = cache.recuperar("assinatura:list:versao:usuario-uuid");

    assertThat(valor).as("Valor recuperado").contains("3");
  }

  @Test
  @DisplayName("Deve retornar vazio para chave inexistente")
  void deveRetornarVazioParaChaveInexistente() {
    when(redisTemplate.opsForValue()).thenReturn(operacoes);
    when(operacoes.get("chave")).thenReturn(null);

    assertThat(cache.recuperar("chave")).as("Chave ausente deve resultar em vazio").isEmpty();
  }

  @Test
  @DisplayName("Deve degradar para miss quando o redis falha na leitura")
  void deveDegradarParaMissQuandoRedisFalhaNaLeitura() {
    when(redisTemplate.opsForValue()).thenReturn(operacoes);
    when(operacoes.get("chave")).thenThrow(new RuntimeException("indisponivel"));

    assertThatCode(() -> cache.recuperar("chave"))
        .as("Falha do redis na leitura nao deve propagar excecao")
        .doesNotThrowAnyException();
    assertThat(cache.recuperar("chave")).as("Leitura com redis fora degrada para vazio").isEmpty();
  }

  @Test
  @DisplayName("Deve gravar o valor na chave com o tempo de vida informado")
  void deveGravarValorComTempoDeVida() {
    when(redisTemplate.opsForValue()).thenReturn(operacoes);

    cache.gravar("chave", "valor", Duration.ofMinutes(5));

    verify(operacoes).set("chave", "valor", Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("Deve degradar silenciosamente quando o redis falha na gravacao")
  void deveDegradarQuandoRedisFalhaNaGravacao() {
    when(redisTemplate.opsForValue()).thenReturn(operacoes);
    doThrow(new RuntimeException("indisponivel"))
        .when(operacoes)
        .set("chave", "valor", Duration.ofMinutes(5));

    assertThatCode(() -> cache.gravar("chave", "valor", Duration.ofMinutes(5)))
        .as("Falha do redis na gravacao nao deve propagar excecao")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Deve incrementar o contador somente apos o commit da transacao")
  void deveIncrementarContadorSomenteAposCommit() {
    TransactionSynchronizationManager.initSynchronization();
    when(redisTemplate.opsForValue()).thenReturn(operacoes);

    cache.invalidarAposCommit("assinatura:list:versao:usuario-uuid");
    verify(operacoes, never()).increment(any());

    TransactionSynchronizationUtils.triggerAfterCommit();

    verify(operacoes).increment("assinatura:list:versao:usuario-uuid");
  }

  @Test
  @DisplayName("Nao deve propagar falha do redis na invalidacao pos commit")
  void naoDevePropagarFalhaDoRedisNaInvalidacao() {
    TransactionSynchronizationManager.initSynchronization();
    when(redisTemplate.opsForValue()).thenReturn(operacoes);
    when(operacoes.increment(any())).thenThrow(new RuntimeException("indisponivel"));

    cache.invalidarAposCommit("assinatura:list:versao:usuario-uuid");

    assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit)
        .as("Falha do redis na invalidacao nao deve propagar excecao")
        .doesNotThrowAnyException();
  }
}
