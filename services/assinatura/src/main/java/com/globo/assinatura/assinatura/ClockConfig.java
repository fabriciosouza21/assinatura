package com.globo.assinatura.assinatura;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracao do relogio da aplicacao.
 *
 * <p>Expoe um {@link Clock} baseado no fuso horario do sistema para injecao nos componentes que
 * calculam datas de vigencia de forma testavel com {@link Clock#fixed(java.time.Instant,
 * java.time.ZoneId)} em testes.
 */
@Configuration
public class ClockConfig {

  /**
   * Retorna um relogio baseado no fuso horario padrao do sistema.
   *
   * @return relogio do sistema
   */
  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
