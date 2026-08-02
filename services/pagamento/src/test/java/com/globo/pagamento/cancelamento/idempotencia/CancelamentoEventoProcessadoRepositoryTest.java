package com.globo.pagamento.cancelamento.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/** Teste de integracao do {@link CancelamentoEventoProcessadoRepository} contra o Postgres real. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class CancelamentoEventoProcessadoRepositoryTest {

  @Autowired private CancelamentoEventoProcessadoRepository eventoProcessadoRepository;

  @Test
  @DisplayName("Deve reservar somente a primeira entrega do evento")
  void deveReservarSomentePrimeiraEntregaDoEvento() {
    UUID eventId = UUID.randomUUID();
    UUID assinaturaId = UUID.randomUUID();

    int primeiraReserva = eventoProcessadoRepository.registrarSeNovo(eventId, assinaturaId);
    int reservaDuplicada = eventoProcessadoRepository.registrarSeNovo(eventId, assinaturaId);

    assertThat(primeiraReserva).as("Primeira reserva do evento").isEqualTo(1);
    assertThat(reservaDuplicada).as("Reserva duplicada do evento").isZero();
  }
}
