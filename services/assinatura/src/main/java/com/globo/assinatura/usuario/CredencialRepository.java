package com.globo.assinatura.usuario;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório de acesso aos dados de usuários. */
public interface CredencialRepository extends JpaRepository<Credencial, Long> {

  /**
   * Busca um usuário pelo nome de login.
   *
   * @param username nome de login do usuário
   * @return o usuário encontrado, ou {@code Optional.empty()} se não existir
   */
  Optional<Credencial> findByUsername(String username);
}
