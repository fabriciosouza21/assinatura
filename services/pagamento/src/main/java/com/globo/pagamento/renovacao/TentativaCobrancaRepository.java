package com.globo.pagamento.renovacao;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repositorio de persistencia do agregado {@link TentativaCobranca}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface TentativaCobrancaRepository extends JpaRepository<TentativaCobranca, Long> {

  /**
   * Seleciona as tentativas de cobranca ainda nao enviadas ao gateway de pagamento.
   *
   * @return tentativas sem {@code payment_id} (nao cobradas pelo gateway)
   */
  @Query(
      nativeQuery = true,
      value = "SELECT * FROM tentativa_cobranca WHERE payment_id IS NULL FOR UPDATE SKIP LOCKED")
  List<TentativaCobranca> buscarProntasParaCobrar();
}
