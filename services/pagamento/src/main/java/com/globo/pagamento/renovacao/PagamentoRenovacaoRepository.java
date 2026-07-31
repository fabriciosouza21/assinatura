package com.globo.pagamento.renovacao;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de persistencia do agregado {@link PagamentoRenovacao}.
 *
 * <p>A idempotencia por {@code renovacaoId} e declarativa: {@link #inserirSeNaoExistir} usa {@code
 * ON CONFLICT (renovacao_id) DO NOTHING} e retorna o numero de linhas afetadas, permitindo ao
 * caller saber se a insercao ocorreu.
 */
public interface PagamentoRenovacaoRepository extends JpaRepository<PagamentoRenovacao, Long> {

  /**
   * Insere o pagamento da renovacao apenas se {@code renovacaoId} ainda nao existir.
   *
   * <p>A unicidade por {@code renovacaoId} fica no banco ({@code UNIQUE (renovacao_id)}). Um
   * redelivery dispara o mesmo {@code INSERT}, que e descartado pelo {@code ON CONFLICT DO
   * NOTHING}, sem duplicar. O retorno indica se a linha foi de fato inserida (1) ou descartada (0).
   *
   * @param renovacaoId identificador publico da renovacao
   * @param assinaturaId identificador publico da assinatura
   * @param plano plano contratado
   * @param valor valor mensal em reais
   * @param cicloReferencia numero do ciclo da renovacao
   * @return {@code 1} se a linha foi inserida; {@code 0} se ja existia (conflito)
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO pagamento_renovacao (
              renovacao_id, assinatura_id, plano, valor, ciclo_referencia,
              criado_em, atualizado_em)
          VALUES (
              :renovacaoId, :assinaturaId, :plano, :valor, :cicloReferencia,
              now(), now())
          ON CONFLICT (renovacao_id) DO NOTHING
          """,
      nativeQuery = true)
  int inserirSeNaoExistir(
      @Param("renovacaoId") String renovacaoId,
      @Param("assinaturaId") String assinaturaId,
      @Param("plano") Plano plano,
      @Param("valor") BigDecimal valor,
      @Param("cicloReferencia") int cicloReferencia);
}
