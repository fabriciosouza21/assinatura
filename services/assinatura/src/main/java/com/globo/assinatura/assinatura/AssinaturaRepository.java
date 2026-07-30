package com.globo.assinatura.assinatura;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

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

  /**
   * Busca uma assinatura pelo uuid publico adquirindo um lock pessimista de escrita.
   *
   * <p>A query derivada e executada com {@code SELECT ... FOR UPDATE} ({@link
   * LockModeType#PESSIMISTIC_WRITE}) e timeout de 3 segundos, garantindo exclusao mutua no
   * processamento concorrente de eventos de pagamento para a mesma assinatura.
   *
   * @param uuid uuid publico da assinatura
   * @return a assinatura encontrada sob lock, ou vazio se nao existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
  Optional<Assinatura> findByUuidForUpdate(String uuid);
}
