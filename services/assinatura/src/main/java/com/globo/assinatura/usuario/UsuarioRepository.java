package com.globo.assinatura.usuario;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Usuario}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

  /**
   * Verifica se ja existe um usuario cadastrado com o email informado.
   *
   * @param email email a ser verificado
   * @return {@code true} se o email ja estiver cadastrado; {@code false} caso contrario
   */
  boolean existsByEmail(String email);

  /**
   * Busca um usuario pelo uuid publico.
   *
   * @param uuid uuid publico do usuario
   * @return o usuario encontrado, ou vazio se nao existir
   */
  Optional<Usuario> findByUuid(String uuid);
}
