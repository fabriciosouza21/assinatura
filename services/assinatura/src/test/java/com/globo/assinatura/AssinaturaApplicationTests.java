package com.globo.assinatura;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Teste de carga do contexto da aplicacao.
 *
 * <p>Desativado enquanto depende do Postgres do docker-compose na porta 5433 do host. O datasource
 * padrao aponta para {@code localhost:5432}, que colide com outro projeto do ambiente local; rode
 * contra o compose exportando {@code
 * SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/assinatura} para validar a subida real, ou
 * aguarde o teste de integracao do BE-1 (com Testcontainers).
 */
@Disabled(
    "depende do postgres do compose (host:5433); exporte SPRING_DATASOURCE_URL"
        + " ou rode o teste de integracao com Testcontainers quando disponivel")
@SpringBootTest
class AssinaturaApplicationTests {

  @Test
  void contextLoads() {}
}
