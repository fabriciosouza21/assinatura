package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

  private final ClockConfig config = new ClockConfig();

  @Test
  @DisplayName("Deve fixar o fuso horario de negocio America/Sao_Paulo independente do host")
  void deveFixarFusoHorarioDeNegocio() {
    Clock clock = config.clock();

    assertThat(clock.getZone())
        .as("o clock nao deve depender do fuso horario implicito do host/container")
        .isEqualTo(ZoneId.of("America/Sao_Paulo"));
  }
}
