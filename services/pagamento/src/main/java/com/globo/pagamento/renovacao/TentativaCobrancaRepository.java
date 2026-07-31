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
   * Seleciona as tentativas de cobranca prontas para serem cobradas pelo scheduler.
   *
   * @return tentativas elegiveis para cobranca
   */
  @Query(nativeQuery = true, value = "SELECT * FROM tentativa_cobranca")
  List<TentativaCobranca> buscarProntasParaCobrar();
}
