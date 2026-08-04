package com.globo.assinatura.cancelamento;

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
 * Prova de ponta a ponta de que o cancelamento exige token JWT no contexto completo da aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, command real, datasource e Flyway
 * contra o Postgres do docker-compose na porta 5433) e dispara requisicoes HTTP reais sem
 * credenciais e com token invalido, via {@link HttpClient} do JDK. Espera-se {@code 401} em ambos
 * os casos.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class CancelaAssinaturaSemTokenTest {

  @LocalServerPort private int port;

  private HttpResponse<String> cancelar(String authorization) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assinaturas/qualquer-uuid/cancelamento"))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(builder.build(), BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Deve recusar cancelamento sem token com 401")
  void deveRecusarCancelamentoSemToken() throws Exception {
    HttpResponse<String> resposta = cancelar(null);

    assertThat(resposta.statusCode())
        .as("Cancelamento sem token deve ser bloqueado pela seguranca")
        .isEqualTo(401);
  }

  @Test
  @DisplayName("Deve recusar cancelamento com token invalido com 401")
  void deveRecusarCancelamentoComTokenInvalido() throws Exception {
    HttpResponse<String> resposta = cancelar("Bearer nao-e-um-jwt-valido");

    assertThat(resposta.statusCode())
        .as("Token invalido deve ser bloqueado pela seguranca")
        .isEqualTo(401);
  }
}
