package com.globo.assinatura.shared.outbox.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova de ponta a ponta de que a recuperacao manual da outbox exige token JWT no contexto completo
 * da aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, datasource e Flyway contra o
 * Postgres do docker-compose na porta 5433) e dispara requisicoes HTTP reais sem credenciais, via
 * {@link HttpClient} do JDK. Espera-se {@code 401} na listagem e na retomada.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class OutboxFalhaSemTokenTest {

  @LocalServerPort private int port;

  @Test
  @DisplayName("Deve recusar a listagem de eventos em falha sem token com 401")
  void deveRecusarListagemSemToken() throws Exception {
    HttpResponse<String> resposta =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/outbox/falhas"))
                    .GET()
                    .build(),
                BodyHandlers.ofString());

    assertThat(resposta.statusCode())
        .as("Listagem sem token deve ser bloqueada pela seguranca")
        .isEqualTo(401);
  }

  @Test
  @DisplayName("Deve recusar a retomada de evento sem token com 401")
  void deveRecusarRetomadaSemToken() throws Exception {
    HttpResponse<String> resposta =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/outbox/falhas/"
                                + UUID.randomUUID()
                                + "/retomada"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                BodyHandlers.ofString());

    assertThat(resposta.statusCode())
        .as("Retomada sem token deve ser bloqueada pela seguranca")
        .isEqualTo(401);
  }
}
