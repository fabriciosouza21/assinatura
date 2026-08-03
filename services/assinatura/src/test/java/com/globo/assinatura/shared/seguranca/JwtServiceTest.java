package com.globo.assinatura.shared.seguranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes do {@link JwtService} cobrindo o ciclo de emissao e leitura das claims de identidade:
 * subject, papel e uuid do usuario de dominio, alem da rejeicao de tokens invalidos.
 */
class JwtServiceTest {

  private static final String SECRET = "segredo-suficientemente-longo-para-hs256-aaaa-bbbb";
  private static final String OUTRO_SECRET = "outro-segredo-longo-para-hs256-cccc-dddd-eeee-ffff";
  private static final String USUARIO_UUID = "550e8400-e29b-41d4-a716-446655440000";

  private final JwtService jwtService = new JwtService(SECRET, 3600000L);

  @Test
  @DisplayName("Deve extrair email, usuarioId e role de um token emitido para cliente")
  void deveExtrairIdentidadeDeTokenDeCliente() {
    String token = jwtService.generateToken("cliente@example.com", "ROLE_CLIENT", USUARIO_UUID);

    Optional<UsuarioAutenticado> principal = jwtService.extrairPrincipal(token);

    assertThat(principal).as("Token valido deve produzir principal").isPresent();
    assertThat(principal.get().email()).as("Email do principal").isEqualTo("cliente@example.com");
    assertThat(principal.get().usuarioId())
        .as("Uuid do usuario de dominio")
        .isEqualTo(USUARIO_UUID);
    assertThat(principal.get().role()).as("Papel do principal").isEqualTo("ROLE_CLIENT");
    assertThat(principal.get().isAdmin()).as("Cliente nao e administrador").isFalse();
  }

  @Test
  @DisplayName("Deve extrair principal sem usuarioId de um token emitido para administrador")
  void deveExtrairIdentidadeDeTokenDeAdministrador() {
    String token = jwtService.generateToken("admin", UsuarioAutenticado.ROLE_ADMIN, null);

    Optional<UsuarioAutenticado> principal = jwtService.extrairPrincipal(token);

    assertThat(principal).as("Token valido deve produzir principal").isPresent();
    assertThat(principal.get().usuarioId())
        .as("Administrador nao tem usuario de dominio ligado")
        .isNull();
    assertThat(principal.get().isAdmin()).as("Papel de administrador").isTrue();
  }

  @Test
  @DisplayName("Deve rejeitar token assinado com outro segredo")
  void deveRejeitarTokenAssinadoComOutroSegredo() {
    String token =
        new JwtService(OUTRO_SECRET, 3600000L)
            .generateToken("cliente@example.com", "ROLE_CLIENT", USUARIO_UUID);

    assertThat(jwtService.extrairPrincipal(token))
        .as("Token de outro emissor deve ser rejeitado")
        .isEmpty();
  }

  @Test
  @DisplayName("Deve rejeitar token expirado")
  void deveRejeitarTokenExpirado() {
    String token =
        new JwtService(SECRET, -1000L).generateToken("cliente@example.com", "ROLE_CLIENT", null);

    assertThat(jwtService.extrairPrincipal(token))
        .as("Token expirado deve ser rejeitado")
        .isEmpty();
  }

  @Test
  @DisplayName("Deve rejeitar token malformado")
  void deveRejeitarTokenMalformado() {
    assertThat(jwtService.extrairPrincipal("nao-e-um-jwt"))
        .as("Token malformado deve ser rejeitado")
        .isEmpty();
  }

  @Test
  @DisplayName("Deve falhar ao construir com segredo em branco")
  void deveFalharComSegredoEmBranco() {
    assertThatThrownBy(() -> new JwtService("", 3600000L))
        .as("Segredo em branco deve impedir o startup com mensagem clara")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APP_JWT_SECRET");
  }
}
