package com.globo.assinatura.usuario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link UsuarioRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao V2 cria a tabela {@code usuarios} e que o agregado {@link Usuario}
 * persiste com identidade tecnica gerada pelo banco e uuid publico atribuido em memoria. Usa o
 * Postgres configurado no docker-compose (porta 5433) e delega o schema ao Flyway. Marcado com
 * {@code @Tag("integration")} para ser isolado dos testes unitarios via {@code make
 * test-integration} / {@code make test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class UsuarioRepositoryTest {

  @Autowired private UsuarioRepository repository;

  @Test
  @DisplayName("Deve persistir usuario e gerar id e uuid publico")
  void devePersistirUsuario() {
    Usuario salvo = repository.save(new Usuario("Fulano", "fulano@example.com"));

    assertThat(salvo.getId()).as("Id tecnico gerado pelo banco").isNotNull();
    assertThat(salvo.getUuid())
        .as("Uuid publico no formato cananico")
        .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    assertThat(repository.existsByEmail("fulano@example.com"))
        .as("Email cadastrado fica detectavel")
        .isTrue();
  }
}
