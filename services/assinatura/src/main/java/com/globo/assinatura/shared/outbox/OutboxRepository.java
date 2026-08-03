package com.globo.assinatura.shared.outbox;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /**
   * Busca um evento pelo identificador, bloqueando a linha para escrita ate o fim da transacao.
   *
   * <p>Garante que chamadas concorrentes de retomada manual sobre o mesmo evento se serializem: a
   * segunda transacao aguarda o lock e observa o status ja transicionado, abortando a propria
   * retomada.
   *
   * @param eventId identificador do evento
   * @return evento encontrado, ou vazio se nao existir
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT e FROM OutboxEvent e WHERE e.eventId = :eventId")
  Optional<OutboxEvent> buscarPorIdComLock(@Param("eventId") UUID eventId);

  /**
   * Seleciona paginadamente os eventos em {@code FALHA} para a recuperacao manual assistida,
   * filtrados por tipo de evento e tempo decorrido desde a falha.
   *
   * @param tipoEvento tipo de evento para filtro exato, ou {@code null} para listar todos
   * @param limiteFalhouEm somente eventos cuja falha ocorreu ate este instante (falhou ha N
   *     segundos)
   * @param pageable paginacao e ordenacao, sempre por instante da falha do mais antigo
   * @return pagina de eventos em falha do mais antigo ao mais recente
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT * FROM outbox "
              + "WHERE status = 'FALHA' "
              + "AND falhou_em <= :limiteFalhouEm "
              + "AND (:tipoEvento IS NULL OR event_type = :tipoEvento) "
              + "ORDER BY falhou_em",
      countQuery =
          "SELECT COUNT(*) FROM outbox "
              + "WHERE status = 'FALHA' "
              + "AND falhou_em <= :limiteFalhouEm "
              + "AND (:tipoEvento IS NULL OR event_type = :tipoEvento)")
  Page<OutboxEvent> buscarFalhas(
      @Param("tipoEvento") String tipoEvento,
      @Param("limiteFalhouEm") Instant limiteFalhouEm,
      Pageable pageable);

  /**
   * Conta os eventos da outbox agrupados por {@code status} monitorado, para alimentar as metricas
   * de monitoramento do ciclo de publicacao e recuperacao.
   *
   * <p>Cada linha contem o nome do status na primeira posicao e a quantidade de eventos na segunda,
   * uma linha por status existente. Filtra apenas os status monitorados ({@code PENDENTE}, {@code
   * RETENTATIVA_DLQ} e {@code FALHA}), mantendo a consulta coberta pelos indices parciais em vez de
   * varrer a tabela inteira.
   *
   * @return linhas de status e contagem
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT status, COUNT(*) FROM outbox "
              + "WHERE status IN ('PENDENTE', 'RETENTATIVA_DLQ', 'FALHA') "
              + "GROUP BY status")
  List<Object[]> contarPorStatus();
}
