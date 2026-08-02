package com.globo.assinatura.shared.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RenovacaoSolicitadaTest {

  // Jackson 3 (tools.jackson), o mesmo que o Spring Boot 4 configura em producao.
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  @DisplayName("Deve serializar o evento com os campos do contrato")
  void deveSerializarEventoComOsCamposDoContrato() throws Exception {
    RenovacaoSolicitada evento =
        new RenovacaoSolicitada(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-31T10:00:00Z"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            Plano.PREMIUM,
            new BigDecimal("39.90"),
            2);

    String json = mapper.writeValueAsString(evento);

    assertThat(json)
        .as("Payload carrega os campos do contrato de renovacao")
        .contains("\"renovacaoId\":\"22222222-2222-2222-2222-222222222222\"")
        .contains("\"assinaturaId\":\"33333333-3333-3333-3333-333333333333\"")
        .contains("\"plano\":\"PREMIUM\"")
        .contains("\"valor\":39.90")
        .contains("\"cicloReferencia\":2");
  }
}
