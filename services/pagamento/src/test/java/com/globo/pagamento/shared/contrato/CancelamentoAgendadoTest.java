package com.globo.pagamento.shared.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Teste unitario do contrato local {@link CancelamentoAgendado}. */
class CancelamentoAgendadoTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve desserializar o contrato de cancelamento agendado")
  void deveDesserializarContratoDeCancelamentoAgendado() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-08-02T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "status": "ATIVA",
          "fimCiclo": "2026-09-01"
        }
        """;

    CancelamentoAgendado evento = jsonMapper.readValue(payload, CancelamentoAgendado.class);

    assertThat(evento.eventId())
        .as("eventId desserializado")
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    assertThat(evento.ocorridoEm())
        .as("ocorridoEm desserializado")
        .isEqualTo(Instant.parse("2026-08-02T12:00:00Z"));
    assertThat(evento.assinaturaId())
        .as("assinaturaId desserializado")
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000011"));
    assertThat(evento.status()).as("status desserializado").isEqualTo(StatusAssinatura.ATIVA);
    assertThat(evento.fimCiclo())
        .as("fimCiclo desserializado")
        .isEqualTo(LocalDate.parse("2026-09-01"));
  }
}
