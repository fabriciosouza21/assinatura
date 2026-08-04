package com.globo.assinatura.shared.seguranca;

/**
 * Identidade extraida do token JWT e publicada como principal do contexto de seguranca.
 *
 * @param email login do usuario, correspondente ao subject do token
 * @param usuarioId uuid publico do usuario de dominio dono dos recursos, ou {@code null} para
 *     credenciais sem usuario de dominio ligado, como o administrador
 * @param role papel do usuario, usado como authority na cadeia de seguranca
 */
public record UsuarioAutenticado(String email, String usuarioId, String role) {

  /** Papel do administrador, autorizado a consultar assinaturas de qualquer usuario. */
  public static final String ROLE_ADMIN = "ROLE_ADMIN";

  /**
   * Indica se a identidade e de um administrador.
   *
   * @return {@code true} se o papel for {@code ROLE_ADMIN}
   */
  public boolean isAdmin() {
    return ROLE_ADMIN.equals(role);
  }
}
