package com.globo.pagamento.renovacao;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
   * <p>A coluna {@code plano} e {@code VARCHAR(32)} mapeada na entidade com
   * {@code @Enumerated(EnumType.STRING)}. Como esta e uma query nativa, o bind do enum nao respeita
   * o mapeamento da entidade (gravaria o ordinal, nao o nome). Por isso o parametro {@code plano} e
   * o {@code name()} do enum, passado como {@link String}, para que a coluna receba {@code BASICO}
   * em vez de {@code 0}.
   *
   * @param renovacaoId identificador publico da renovacao
   * @param assinaturaId identificador publico da assinatura
   * @param plano nome do plano contratado (use {@link Plano#name()})
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
      @Param("plano") String plano,
      @Param("valor") BigDecimal valor,
      @Param("cicloReferencia") int cicloReferencia);

  /**
   * Busca o pagamento da renovacao pelo identificador publico da renovacao.
   *
   * @param renovacaoId identificador publico da renovacao (chave de idempotencia)
   * @return pagamento da renovacao, ou vazio se nao existir
   */
  Optional<PagamentoRenovacao> findByRenovacaoId(String renovacaoId);

  /**
   * Busca os pagamentos de renovacao vinculados a uma assinatura.
   *
   * @param assinaturaId identificador publico da assinatura
   * @return pagamentos de renovacao encontrados para a assinatura
   */
  List<PagamentoRenovacao> findByAssinaturaId(String assinaturaId);

  /**
   * Busca o pagamento de renovacao mais recente de uma assinatura.
   *
   * @param assinaturaId identificador publico da assinatura renovada
   * @return pagamento da renovacao mais recente, ou vazio se a assinatura nunca renovou
   */
  Optional<PagamentoRenovacao> findFirstByAssinaturaIdOrderByCriadoEmDesc(String assinaturaId);
}
