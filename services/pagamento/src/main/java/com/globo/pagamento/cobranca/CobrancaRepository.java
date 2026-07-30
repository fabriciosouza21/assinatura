package com.globo.pagamento.cobranca;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Cobranca}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface CobrancaRepository extends JpaRepository<Cobranca, Long> {

  /**
   * Busca a cobranca correlacionada a uma assinatura.
   *
   * @param assinaturaUuid identificador publico da assinatura
   * @return a cobranca encontrada, ou vazio se nao existir
   */
  Optional<Cobranca> findByAssinaturaUuid(String assinaturaUuid);

  /**
   * Verifica se ja existe cobranca correlacionada a uma assinatura.
   *
   * @param assinaturaUuid identificador publico da assinatura
   * @return {@code true} se existir; {@code false} caso contrario
   */
  boolean existsByAssinaturaUuid(String assinaturaUuid);
}
