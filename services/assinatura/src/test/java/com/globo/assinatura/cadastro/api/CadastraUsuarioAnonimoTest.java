package com.globo.assinatura.cadastro.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Prova de ponta a ponta de que o cadastro de usuario em {@code POST /usuarios} e acessivel de
 * forma anonima (sem cabecalho {@code Authorization}) no contexto completo da aplicacao.
 *
 * <p>Sobe o contexto Spring inteiro (filtro de seguranca real, {@link CadastrarUsuario} real,
 * datasource e Flyway contra o Postgres do docker-compose na porta 5433) e dispara uma requisicao
 * HTTP real, sem credenciais, via {@link HttpClient} do JDK. Espera-se {@code 201 Created},
 * provando que a cadeia de filtros libera a rota para auto-cadastro. Marcado com
 * {@code @Tag("integration")} para ser isolado dos testes unitarios via {@code make test} / {@code
 * make test-integration}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class CadastraUsuarioAnonimoTest {

  @LocalServerPort private int port;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("Deve permitir cadastro anonimo em /usuarios")
  void devePermitirCadastroAnonimo() throws Exception {
    String emailUnico = "anonimo-" + UUID.randomUUID() + "@example.com";
    String corpo =
        objectMapper.writeValueAsString(new UsuarioRequest("Fulano", emailUnico, "SenhaForte1"));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/usuarios"))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(corpo))
            .build();

    HttpResponse<String> resp = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());

    assertThat(resp.statusCode())
        .as("Cadastro anonimo deve ser permitido")
        .isEqualTo(HttpStatus.CREATED.value());
  }
}
