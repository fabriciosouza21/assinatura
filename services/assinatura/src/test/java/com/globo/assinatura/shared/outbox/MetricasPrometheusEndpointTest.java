package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova ponta a ponta do endpoint de metricas exposto para o Prometheus.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, datasource e Flyway contra o
 * Postgres do docker-compose na porta 5433) e dispara requisicao HTTP real sem credenciais, via
 * {@link HttpClient} do JDK. Espera-se que {@code /actuator/prometheus} responda {@code 200}
 * contendo o gauge {@code outbox_eventos} da outbox e que {@code /actuator/health} continue
 * publico.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class MetricasPrometheusEndpointTest {

  @LocalServerPort private int port;

  private HttpResponse<String> get(String caminho) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + caminho))
            .GET()
            .build();
    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Deve expor o gauge de eventos da outbox em /actuator/prometheus sem token")
  void deveExporGaugeDaOutboxSemToken() throws Exception {
    HttpResponse<String> resposta = get("/actuator/prometheus");

    assertThat(resposta.statusCode())
        .as("Endpoint de metricas acessivel sem token para o scraper")
        .isEqualTo(200);
    assertThat(resposta.body())
        .as("Corpo do scrape contem o gauge de eventos da outbox por status")
        .contains("outbox_eventos")
        .contains("servico=\"assinatura\"")
        .contains("status=\"falha\"");
  }

  @Test
  @DisplayName("Deve manter /actuator/health publico")
  void deveManterHealthPublico() throws Exception {
    HttpResponse<String> resposta = get("/actuator/health");

    assertThat(resposta.statusCode()).as("Health continua acessivel sem token").isEqualTo(200);
  }
}
