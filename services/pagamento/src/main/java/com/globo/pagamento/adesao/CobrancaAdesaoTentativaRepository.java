package com.globo.pagamento.adesao;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repositorio de persistencia do agregado {@link CobrancaAdesaoTentativa}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface CobrancaAdesaoTentativaRepository
    extends JpaRepository<CobrancaAdesaoTentativa, Long> {

  /**
   * Seleciona as tentativas de cobranca de adesao ainda nao enviadas ao gateway de pagamento.
   *
   * <p>Uma tentativa e elegivel quando esta {@code PENDENTE}, ainda nao tem {@code payment_id} e o
   * agendamento venceu. A primeira tentativa nasce sem agendamento ({@code NULL}) e e cobrada de
   * imediato; tentativas em backoff apos falha tecnica nascem com {@code proxima_tentativa_em} no
   * futuro, e o predicado temporal e o que faz o backoff valer.
   *
   * <p>O {@code FOR UPDATE SKIP LOCKED} impede cobranca duplicada quando mais de uma instancia do
   * Pagamento roda em paralelo. O {@code LIMIT 200} corta o lote por ciclo, evitando prender
   * conexao e persistence context por centenas de roundtrips ao gateway.
   *
   * @return tentativas pendentes, nao cobradas e com agendamento vencido, limitadas a 200
   */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT * FROM cobranca_adesao_tentativa
          WHERE status = 'PENDENTE'
            AND payment_id IS NULL
            AND (proxima_tentativa_em IS NULL OR proxima_tentativa_em <= now())
          ORDER BY id
          FOR UPDATE SKIP LOCKED
          LIMIT 200
          """)
  List<CobrancaAdesaoTentativa> buscarProntasParaCobrar();

  /**
   * Busca a tentativa de adesao pelo identificador publico da assinatura.
   *
   * @param assinaturaUuid identificador publico da assinatura
   * @return tentativa correspondente, ou vazio se nao existir
   */
  Optional<CobrancaAdesaoTentativa> findByAssinaturaUuid(String assinaturaUuid);

  /**
   * Informa se ja existe tentativa para a assinatura.
   *
   * @param assinaturaUuid identificador publico da assinatura
   * @return {@code true} se ja existir tentativa
   */
  boolean existsByAssinaturaUuid(String assinaturaUuid);
}
