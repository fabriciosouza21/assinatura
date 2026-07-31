package com.globo.pagamento.cobranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link CobrancaRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao V1 cria a tabela {@code cobranca}, que o agregado {@link Cobranca}
 * persiste alinhado ao schema e que a busca por {@code assinaturaUuid} suporta a idempotencia do
 * consumer. Usa o Postgres do docker-compose (porta 5433, banco {@code pagamento}).
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
class CobrancaRepositoryTest {

  @Autowired private CobrancaRepository cobrancaRepository;

  @Test
  @DisplayName("Deve persistir e buscar cobranca por assinatura uuid")
  void deveBuscarCobrancaPersistida() {
    Cobranca cobranca =
        cobrancaRepository.save(new Cobranca("assinatura-uuid", "pay_123", StatusCobranca.PENDING));
    cobrancaRepository.flush();

    Optional<Cobranca> encontrado = cobrancaRepository.findByAssinaturaUuid("assinatura-uuid");

    assertThat(encontrado).as("Cobranca encontrada pela correlacao").isPresent();
    assertThat(encontrado.get().getPaymentId()).as("PaymentId persistido").isEqualTo("pay_123");
    assertThat(encontrado.get().getStatus()).as("Status inicial").isEqualTo(StatusCobranca.PENDING);
    assertThat(encontrado.get().getCriadoEm()).as("CriadoEm preenchido").isNotNull();
    assertThat(encontrado.get().getAtualizadoEm()).as("AtualizadoEm preenchido").isNotNull();
  }
}
