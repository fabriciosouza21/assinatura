package com.globo.assinatura.assinatura;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Renovacao}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface RenovacaoRepository extends JpaRepository<Renovacao, Long> {

  /**
   * Verifica se ja existe renovacao para a assinatura no ciclo informado.
   *
   * <p>Base da idempotencia por ciclo: impede que o scheduler crie uma segunda renovacao para o
   * mesmo vencimento em execucoes subsequentes.
   *
   * @param assinaturaId identificador interno da assinatura
   * @param cicloReferencia fim do ciclo renovado
   * @return {@code true} se ja existir renovacao para o ciclo; {@code false} caso contrario
   */
  boolean existsByAssinaturaIdAndCicloReferencia(Long assinaturaId, LocalDate cicloReferencia);

  /**
   * Conta quantas renovações ja existem para a assinatura.
   *
   * <p>Suporta o calculo do ordinal do proximo ciclo: {@code count + 1}.
   *
   * @param assinaturaId identificador interno da assinatura
   * @return numero de renovações ja registradas para a assinatura
   */
  long countByAssinaturaId(Long assinaturaId);
}
