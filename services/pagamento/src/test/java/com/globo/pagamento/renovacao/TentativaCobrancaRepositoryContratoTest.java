package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Teste estrutural do contrato de cancelamento em {@link TentativaCobrancaRepository}. */
class TentativaCobrancaRepositoryContratoTest {

  @Test
  @DisplayName("Deve travar tentativas pendentes para cancelamento")
  void deveTravarTentativasPendentesParaCancelamento() throws NoSuchMethodException {
    Method metodo =
        TentativaCobrancaRepository.class.getMethod("buscarPendentesPorRenovacaoId", String.class);
    Lock lock = metodo.getAnnotation(Lock.class);

    assertThat(lock).as("Lock de atualizacao da consulta").isNotNull();
    assertThat(lock.value())
        .as("Modo do lock da consulta")
        .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  @DisplayName("Deve buscar apenas tentativas pendentes ainda nao enviadas")
  void deveBuscarApenasTentativasPendentesAindaNaoEnviadas() throws NoSuchMethodException {
    Method metodo =
        TentativaCobrancaRepository.class.getMethod("buscarPendentesPorRenovacaoId", String.class);
    Query query = metodo.getAnnotation(Query.class);

    assertThat(query).as("Consulta declarada para as tentativas pendentes").isNotNull();
    assertThat(query.value())
        .as("Filtro de status pendente")
        .contains("t.status = com.globo.pagamento.renovacao.StatusTentativa.PENDENTE");
    assertThat(query.value())
        .as("Filtro de tentativa ainda nao enviada ao gateway")
        .containsIgnoringCase("t.paymentId is null");
  }
}
