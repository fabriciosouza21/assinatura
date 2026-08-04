package com.globo.pagamento.renovacao;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de persistencia do agregado {@link TentativaCobranca}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface TentativaCobrancaRepository extends JpaRepository<TentativaCobranca, Long> {

  /**
   * Seleciona as tentativas de cobranca ainda nao enviadas ao gateway de pagamento.
   *
   * <p>Uma tentativa e elegivel quando esta {@code PENDENTE}, ainda nao tem {@code payment_id} e o
   * agendamento venceu. As tentativas de retry criadas apos uma recusa nascem com {@code
   * proxima_tentativa_em} no futuro, e o predicado temporal e o que faz o backoff valer: sem ele,
   * elas seriam cobradas no ciclo seguinte do scheduler, e nao na data marcada. A primeira
   * tentativa do ciclo nasce sem agendamento ({@code NULL}) e e cobrada de imediato.
   *
   * <p>O {@code LIMIT} corta o lote em um tamanho fixo por ciclo: a transacao do scheduler segura o
   * {@code FOR UPDATE SKIP LOCKED} de cada linha ate o commit, e um lote ilimitado prenderia
   * conexao e persistence context por centenas de roundtrips ao gateway. O que sobrar e selecionado
   * no ciclo seguinte.
   *
   * @return tentativas pendentes, nao cobradas e com o agendamento vencido, limitadas a 200
   */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT * FROM tentativa_cobranca
          WHERE status = 'PENDENTE'
            AND payment_id IS NULL
            AND (proxima_tentativa_em IS NULL OR proxima_tentativa_em <= now())
          ORDER BY id
          FOR UPDATE SKIP LOCKED
          LIMIT 200
          """)
  List<TentativaCobranca> buscarProntasParaCobrar();

  /**
   * Busca as tentativas pendentes de uma renovacao ainda nao enviadas ao gateway.
   *
   * <p>Somente tentativas sem {@code paymentId} podem ser canceladas. O lock de escrita serializa o
   * cancelamento com o scheduler, impedindo que uma tentativa seja enviada ao gateway enquanto esta
   * sendo cancelada. Tentativas travadas pelo scheduler no momento da leitura sao puladas ({@code
   * SKIP LOCKED}): estao em cobranca no gateway e seguem o fluxo normal da decisao.
   *
   * @param renovacaoId identificador publico da renovacao
   * @return tentativas pendentes e ainda nao enviadas da renovacao
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select t from TentativaCobranca t where t.renovacaoId = :renovacaoId "
          + "and t.status = com.globo.pagamento.renovacao.StatusTentativa.PENDENTE "
          + "and t.paymentId is null")
  List<TentativaCobranca> buscarPendentesPorRenovacaoId(@Param("renovacaoId") String renovacaoId);

  /**
   * Busca a tentativa mais recente de uma renovacao.
   *
   * @param renovacaoId identificador publico da renovacao
   * @return tentativa mais recente, ou vazio se a renovacao ainda nao tiver tentativas
   */
  Optional<TentativaCobranca> findFirstByRenovacaoIdOrderByNumeroDesc(String renovacaoId);

  /**
   * Busca a tentativa cobrada sob o identificador de cobranca do gateway.
   *
   * <p>Um {@code paymentId} pertence a uma unica tentativa: e por ele que o webhook ancora a
   * decisao do gateway na tentativa certa.
   *
   * @param paymentId identificador da cobranca no gateway
   * @return tentativa correspondente, ou vazio se nenhuma tiver sido cobrada sob esse identificador
   */
  Optional<TentativaCobranca> findByPaymentId(String paymentId);

  /**
   * Busca a tentativa cobrada sob o identificador de cobranca do gateway, travando-a para escrita
   * ate o fim da transacao.
   *
   * <p>E o caminho usado pelo webhook para decidir: o lock serializa notificacoes concorrentes
   * sobre a mesma cobranca (reenvio do gateway, replay). A segunda transacao bloqueia ate a
   * primeira commitar e, ao prosseguir, enxerga a tentativa ja decidida e a ignora, evitando a
   * dupla publicacao do resultado.
   *
   * @param paymentId identificador da cobranca no gateway
   * @return tentativa correspondente, ou vazio se nenhuma tiver sido cobrada sob esse identificador
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TentativaCobranca t where t.paymentId = :paymentId")
  Optional<TentativaCobranca> buscarPorPaymentIdParaAtualizacao(String paymentId);
}
