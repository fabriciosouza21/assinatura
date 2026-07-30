package com.globo.assinatura.assinatura;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova de ponta a ponta de que a solicitacao de assinatura em {@code POST /assinaturas} e
 * acessivel de forma anonima (sem cabecalho {@code Authorization}) no contexto completo da
 * aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, command/query reais, datasource e
 * Flyway contra o Postgres do docker-compose na porta 5433) e dispara uma requisicao HTTP real, sem
 * credenciais, via {@link HttpClient} do JDK. Espera-se que a requisicao nao seja bloqueada pela
 * cadeia de filtros (status diferente de 401/403). Marcado com {@code @Tag("integration")} para ser
 * isolado dos testes unitarios via {@code make test} / {@code make test-integration}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class SolicitaAssinaturaAnonimoTest {

  @LocalServerPort private int port;

  @Test
  @DisplayName("Deve permitir solicitacao anonima em /assinaturas")
  void devePermitirSolicitacaoAnonima() throws Exception {
    String usuarioId = UUID.randomUUID().toString();
    String corpo = "{\"usuarioId\":\"" + usuarioId + "\",\"plano\":\"BASICO\"}";

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/assinaturas"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(corpo))
            .build();

    HttpResponse<String> resp = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

    assertThat(resp.statusCode())
        .as("Solicitacao anonima nao deve ser bloqueada pela seguranca")
        .isNotIn(401, 403);
  }
}
