package com.globo.assinatura.usuario;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes unitarios do agregado {@link Usuario}.
 *
 * <p>Verifica os invariantes de construcao da entidade, garantindo que estados invalidos sejam
 * rejeitados pelo construtor canonico.
 */
class UsuarioTest {

  @Test
  @DisplayName("Deve rejeitar nome vazio ao construir o usuario")
  void construtorRejeitaNomeVazio() {
    assertThatThrownBy(() -> new Usuario("", "valido@example.com"))
        .as("Construtor deve rejeitar nome vazio")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
