package com.globo.assinatura.consulta;

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
 * Prova de ponta a ponta de que a consulta da assinatura ativa exige token JWT no contexto completo
 * da aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, query real, datasource e Flyway
 * contra o Postgres do docker-compose na porta 5433) e dispara requisicao HTTP real sem
 * credenciais, via {@link HttpClient} do JDK. Espera-se {@code 401}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class ConsultaAssinaturaAtivaSemTokenTest {

  @LocalServerPort private int port;

  private HttpResponse<String> consultar(String authorization) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assinaturas/ativa"))
            .GET();
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(builder.build(), BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Deve recusar consulta da assinatura ativa sem token com 401")
  void deveRecusarConsultaAtivaSemToken() throws Exception {
    HttpResponse<String> resposta = consultar(null);

    assertThat(resposta.statusCode())
        .as("Consulta da assinatura ativa sem token deve ser bloqueada pela seguranca")
        .isEqualTo(401);
  }
}
