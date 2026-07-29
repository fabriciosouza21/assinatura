package com.globo.assinatura.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositório de acesso aos dados de usuários. */
public interface UserRepository extends JpaRepository<User, Long> {

  /**
   * Busca um usuário pelo nome de login.
   *
   * @param username nome de login do usuário
   * @return o usuário encontrado, ou {@code Optional.empty()} se não existir
   */
  Optional<User> findByUsername(String username);
}
