package com.globo.assinatura.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.LocalDate;
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
 * Teste de integracao do {@link RenovacaoRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao V7 cria a tabela {@code renovacao} e que o agregado {@link Renovacao}
 * persiste alinhado ao schema. Usa o Postgres do docker-compose (porta 5433) e delega o schema ao
 * Flyway.
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
class RenovacaoRepositoryTest {

  @Autowired private RenovacaoRepository renovacaoRepository;
  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private UsuarioRepository usuarioRepository;

  @Test
  @DisplayName("Deve persistir renovacao e gerar id")
  void devePersistirRenovacao() {
    Usuario usuario = usuarioRepository.save(new Usuario("Beltrano", "beltrano-renov@example.com"));
    Assinatura assinatura =
        assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.PREMIUM));
    Renovacao salvo =
        renovacaoRepository.saveAndFlush(
            new Renovacao(assinatura.getId(), LocalDate.of(2026, 2, 1), 1));

    assertThat(salvo.getId()).as("Id tecnico gerado pelo banco").isNotNull();
    assertThat(salvo.getUuid()).as("Uuid publico gerado").isNotNull();
  }

  @Test
  @DisplayName("Deve impedir duas renovacoes para o mesmo ciclo da assinatura")
  void deveImpedirDuasRenovacoesParaMesmoCiclo() {
    Usuario usuario = usuarioRepository.save(new Usuario("Ciclano", "ciclano-renov@example.com"));
    Assinatura assinatura =
        assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.BASICO));
    LocalDate cicloReferencia = LocalDate.of(2026, 2, 1);
    renovacaoRepository.saveAndFlush(new Renovacao(assinatura.getId(), cicloReferencia, 1));

    assertThatThrownBy(
            () ->
                renovacaoRepository.saveAndFlush(
                    new Renovacao(assinatura.getId(), cicloReferencia, 1)))
        .as("Indice unico impede segunda renovacao para o mesmo ciclo")
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
