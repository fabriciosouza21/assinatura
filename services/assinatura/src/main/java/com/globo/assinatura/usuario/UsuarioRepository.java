package com.globo.assinatura.usuario;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Usuario}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD sem metodos custom
 * adicionais nesta iteracao.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

  /**
   * Verifica se ja existe um usuario cadastrado com o email informado.
   *
   * @param email email a ser verificado
   * @return {@code true} se o email ja estiver cadastrado; {@code false} caso contrario
   */
  boolean existsByEmail(String email);
}
