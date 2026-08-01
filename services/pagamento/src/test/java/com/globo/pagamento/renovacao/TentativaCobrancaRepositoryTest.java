package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link TentativaCobrancaRepository} contra o Postgres real.
 *
 * <p>Valida o predicado de elegibilidade do scheduler (BE-12): a query {@code
 * buscarProntasParaCobrar()} deve devolver apenas as tentativas pendentes que ainda nao possuem
 * {@code paymentId}, excluindo as que ja foram cobradas pelo gateway. Persiste duas tentativas (uma
 * pronta e uma ja cobrada) e exige que apenas a primeira seja retornada. Usa o Postgres do
 * docker-compose (porta 5433, banco {@code pagamento}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class TentativaCobrancaRepositoryTest {

  @Autowired private TentativaCobrancaRepository tentativaCobrancaRepository;

  @Test
  @DisplayName("Deve retornar apenas as tentativas pendentes sem payment id")
  void deveRetornarApenasTentativasSemPaymentId() {
    TentativaCobranca pronta = new TentativaCobranca("ren-A", 1);
    TentativaCobranca cobrada = new TentativaCobranca("ren-B", 1);
    cobrada.registrarCobranca("pay-1");
    tentativaCobrancaRepository.save(pronta);
    tentativaCobrancaRepository.save(cobrada);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Apenas a tentativa sem payment id e elegivel")
        .extracting(TentativaCobranca::getRenovacaoId)
        .containsExactly("ren-A");
  }
}
