package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova de ponta a ponta de que as rotas de assinatura exigem token JWT no contexto completo da
 * aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, command/query reais, datasource e
 * Flyway contra o Postgres do docker-compose na porta 5433) e dispara requisicoes HTTP reais, sem
 * credenciais e com token invalido, via {@link HttpClient} do JDK. Espera-se {@code 401} em todos
 * os casos, conforme o entry point configurado na cadeia de filtros. Marcado com {@code
 * Tag("integration")} para ser isolado dos testes unitarios via {@code make test} / {@code make
 * test-integration}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class SolicitaAssinaturaSemTokenTest {

  @LocalServerPort private int port;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private HttpResponse<String> solicitar(String authorization) throws Exception {
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(Plano.BASICO));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assinaturas"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(corpo));
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(builder.build(), BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Deve recusar solicitacao sem token em /assinaturas com 401")
  void deveRecusarSolicitacaoSemToken() throws Exception {
    HttpResponse<String> resposta = solicitar(null);

    assertThat(resposta.statusCode())
        .as("Solicitacao sem token deve ser bloqueada pela seguranca")
        .isEqualTo(401);
  }

  @Test
  @DisplayName("Deve recusar solicitacao com token invalido em /assinaturas com 401")
  void deveRecusarSolicitacaoComTokenInvalido() throws Exception {
    HttpResponse<String> resposta = solicitar("Bearer nao-e-um-jwt-valido");

    assertThat(resposta.statusCode())
        .as("Token invalido deve ser bloqueado pela seguranca")
        .isEqualTo(401);
  }

  @Test
  @DisplayName("Deve recusar consulta sem token em /assinaturas/{uuid} com 401")
  void deveRecusarConsultaSemToken() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assinaturas/qualquer-uuid"))
            .GET()
            .build();

    HttpResponse<String> resposta =
        HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

    assertThat(resposta.statusCode())
        .as("Consulta sem token deve ser bloqueada pela seguranca")
        .isEqualTo(401);
  }
}
