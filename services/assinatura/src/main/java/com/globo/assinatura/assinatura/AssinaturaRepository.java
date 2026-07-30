package com.globo.assinatura.assinatura;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Assinatura}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface AssinaturaRepository extends JpaRepository<Assinatura, Long> {

  /**
   * Verifica se existe assinatura para o usuario em algum dos status informados.
   *
   * @param usuarioId identificador interno do usuario
   * @param status colecao de status considerados abertos
   * @return {@code true} se existir ao menos uma assinatura em um dos status; {@code false} caso
   *     contrario
   */
  boolean existsByUsuarioIdAndStatusIn(Long usuarioId, Collection<StatusAssinatura> status);

  /**
   * Busca uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a assinatura encontrada, ou vazio se nao existir
   */
  Optional<Assinatura> findByUuid(String uuid);
}
