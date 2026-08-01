package com.globo.assinatura.assinatura;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracao do relogio da aplicacao.
 *
 * <p>Expoe um {@link Clock} fixado no fuso horario de negocio, para injecao nos componentes que
 * calculam datas de vigencia de forma testavel com {@link Clock#fixed(java.time.Instant,
 * java.time.ZoneId)} em testes.
 */
@Configuration
public class ClockConfig {

  private static final ZoneId FUSO_HORARIO_NEGOCIO = ZoneId.of("America/Sao_Paulo");

  /**
   * Retorna um relogio fixado no fuso horario de negocio, independente do fuso horario implicito do
   * host/container em runtime.
   *
   * @return relogio no fuso America/Sao_Paulo
   */
  @Bean
  public Clock clock() {
    return Clock.system(FUSO_HORARIO_NEGOCIO);
  }
}
