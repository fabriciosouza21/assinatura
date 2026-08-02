package com.globo.pagamento.shared.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Teste unitario do contrato local {@link AssinaturaCancelada}. */
class AssinaturaCanceladaTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve desserializar o contrato de assinatura cancelada")
  void deveDesserializarContratoDeAssinaturaCancelada() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000002",
          "ocorridoEm": "2026-08-02T13:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000012",
          "status": "CANCELADA",
          "fimCiclo": null
        }
        """;

    AssinaturaCancelada evento = jsonMapper.readValue(payload, AssinaturaCancelada.class);

    assertThat(evento.eventId())
        .as("eventId desserializado")
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    assertThat(evento.ocorridoEm())
        .as("ocorridoEm desserializado")
        .isEqualTo(Instant.parse("2026-08-02T13:00:00Z"));
    assertThat(evento.assinaturaId())
        .as("assinaturaId desserializado")
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000012"));
    assertThat(evento.status()).as("status desserializado").isEqualTo(StatusAssinatura.CANCELADA);
    assertThat(evento.fimCiclo()).as("fimCiclo desserializado").isNull();
  }
}
