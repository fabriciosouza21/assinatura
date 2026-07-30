package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link AssinaturaRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao V3 cria a tabela {@code assinatura}, que o agregado {@link Assinatura}
 * persiste alinhado ao schema e que o indice unico parcial impede duas assinaturas abertas para o
 * mesmo usuario. Usa o Postgres do docker-compose (porta 5433) e delega o schema ao Flyway.
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
class AssinaturaRepositoryTest {

  @Autowired private AssinaturaRepository assinaturaRepository;

  @Autowired private UsuarioRepository usuarioRepository;

  @Test
  @DisplayName("Deve persistir assinatura e gerar id com uuid e status aguardando")
  void devePersistirAssinatura() {
    Usuario usuario = usuarioRepository.save(new Usuario("Fulano", "fulano@example.com"));
    Assinatura salvo = assinaturaRepository.save(new Assinatura(usuario.getId(), Plano.PREMIUM));
    assinaturaRepository.flush();

    assertThat(salvo.getId()).as("Id tecnico gerado pelo banco").isNotNull();
    assertThat(salvo.getUuid())
        .as("Uuid publico no formato canonico")
        .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    assertThat(salvo.getStatus())
        .as("Status inicial")
        .isEqualTo(StatusAssinatura.AGUARDANDO_PAGAMENTO);
  }

  @Test
  @DisplayName("Deve impedir duas assinaturas abertas para o mesmo usuario")
  void deveImpedirDuasAssinaturasAbertasParaMesmoUsuario() {
    Usuario usuario = usuarioRepository.save(new Usuario("Cicrano", "cicrano@example.com"));
    assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.BASICO));

    assertThatThrownBy(
            () -> assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.PREMIUM)))
        .as("Indice unico parcial impede segunda assinatura aberta")
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
