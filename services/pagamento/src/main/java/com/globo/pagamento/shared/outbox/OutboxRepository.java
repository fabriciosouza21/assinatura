package com.globo.pagamento.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de persistencia dos eventos da outbox.
 *
 * <p>Expoe operacoes de escrita e leitura sobre {@link OutboxEvent}.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Seleciona eventos prontos para envio, bloqueando as linhas e pulando as ja locked por outra
   * instancia do publisher.
   *
   * <p>Seleciona {@code PENDENTE} e {@code RETENTATIVA_DLQ}: eventos recuperados da DLQ voltam ao
   * ciclo normal de publicacao. Usa {@code FOR UPDATE SKIP LOCKED} para permitir multiplos
   * publicadores concorrentes sem processar a mesma linha.
   *
   * @param agora instante de referencia para readiness
   * @param limite maximo de eventos retornados
   * @return eventos prontos para envio cuja proxima tentativa venceu, do mais antigo ao mais
   *     recente
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT * FROM outbox "
              + "WHERE status IN ('PENDENTE', 'RETENTATIVA_DLQ') "
              + "AND proxima_tentativa_em <= :agora "
              + "ORDER BY criado_em "
              + "FOR UPDATE SKIP LOCKED "
              + "LIMIT :limite")
  List<OutboxEvent> buscarPublicaveis(@Param("agora") Instant agora, @Param("limite") int limite);

  /**
   * Seleciona eventos em {@code FALHA} elegiveis para recuperacao automatica da DLQ, bloqueando as
   * linhas e pulando as ja locked por outra instancia do scheduler.
   *
   * @param limiteFalhouEm somente eventos cuja falha ocorreu ate este instante (timeout vencido)
   * @param maxCiclos maximo de ciclos de recuperacao permitidos
   * @param tamanhoLote maximo de eventos retornados
   * @return eventos em falha elegiveis para recuperacao, do mais antigo ao mais recente
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT * FROM outbox "
              + "WHERE status = 'FALHA' AND falhou_em <= :limiteFalhouEm "
              + "AND ciclos_recuperacao < :maxCiclos "
              + "ORDER BY falhou_em "
              + "FOR UPDATE SKIP LOCKED "
              + "LIMIT :tamanhoLote")
  List<OutboxEvent> buscarRecuperaveis(
      @Param("limiteFalhouEm") Instant limiteFalhouEm,
      @Param("maxCiclos") int maxCiclos,
      @Param("tamanhoLote") int tamanhoLote);
}
