package com.globo.pagamento.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste unitario do evento {@link PagamentoStatusAtualizado}.
 *
 * <p>Garante que o payload publicado segue o contrato travado em {@code
 * docs/contrato-eventos-kafka.puml} e e compativel com o consumer do Assinatura Service.
 */
class PagamentoStatusAtualizadoTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve serializar os campos do contrato travado")
  void deveSerializarOsCamposDoContratoTravado() {
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-07-30T12:00:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            StatusPagamento.APPROVED,
            UUID.fromString("00000000-0000-0000-0000-000000000021"));

    String payload = jsonMapper.writeValueAsString(evento);

    assertThat(payload)
        .as("Payload publicado segue o contrato travado")
        .contains(
            "\"eventId\":\"00000000-0000-0000-0000-000000000001\"",
            "\"ocorridoEm\":\"2026-07-30T12:00:00Z\"",
            "\"assinaturaId\":\"00000000-0000-0000-0000-000000000011\"",
            "\"status\":\"APPROVED\"",
            "\"paymentId\":\"00000000-0000-0000-0000-000000000021\"");
  }
}
