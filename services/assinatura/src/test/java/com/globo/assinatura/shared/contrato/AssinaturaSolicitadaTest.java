package com.globo.assinatura.shared.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class AssinaturaSolicitadaTest {

  // Jackson 3 (tools.jackson), o mesmo que o Spring Boot 4 configura em producao.
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  @DisplayName("Deve serializar o evento com plano e valor do contrato")
  void deveSerializarEventoComPlanoValorDoContrato() throws Exception {
    AssinaturaSolicitada evento =
        new AssinaturaSolicitada(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-30T10:00:00Z"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            Plano.PREMIUM);

    String json = mapper.writeValueAsString(evento);

    assertThat(json)
        .as("Payload carrega plano e valor do contrato")
        .contains("\"plano\":\"PREMIUM\"")
        .contains("\"valor\":39.90");
  }

  static Stream<Arguments> planosDoContrato() {
    return Stream.of(
        Arguments.of(Plano.BASICO, "\"plano\":\"BASICO\"", "\"valor\":19.90"),
        Arguments.of(Plano.PREMIUM, "\"plano\":\"PREMIUM\"", "\"valor\":39.90"),
        Arguments.of(Plano.FAMILIA, "\"plano\":\"FAMILIA\"", "\"valor\":59.90"));
  }

  @ParameterizedTest(name = "Deve serializar {0} com plano e valor do contrato")
  @MethodSource("planosDoContrato")
  void deveSerializarTodosOsPlanos(Plano plano, String planoJson, String valorJson)
      throws Exception {
    AssinaturaSolicitada evento =
        new AssinaturaSolicitada(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-30T10:00:00Z"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            plano);

    String json = mapper.writeValueAsString(evento);

    assertThat(json).as("Plano no payload").contains(planoJson);
    assertThat(json).as("Valor derivado de Plano.valor() no payload").contains(valorJson);
  }
}
