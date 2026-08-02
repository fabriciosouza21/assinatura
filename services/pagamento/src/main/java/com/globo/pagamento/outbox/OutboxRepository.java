package com.globo.pagamento.outbox;

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
   * Seleciona eventos pendentes prontos para envio, bloqueando as linhas e pulando as ja locked por
   * outra instancia do publisher.
   *
   * <p>Usa {@code FOR UPDATE SKIP LOCKED} para permitir multiplos publicadores concorrentes sem
   * processar a mesma linha.
   *
   * @param agora instante de referencia para readiness
   * @param limite maximo de eventos retornados
   * @return eventos pendentes cuja proxima tentativa venceu, do mais antigo ao mais recente
   */
  @Query(
      nativeQuery = true,
      value =
          "SELECT * FROM outbox "
              + "WHERE status = 'PENDENTE' AND proxima_tentativa_em <= :agora "
              + "ORDER BY criado_em "
              + "FOR UPDATE SKIP LOCKED "
              + "LIMIT :limite")
  List<OutboxEvent> buscarPublicaveis(@Param("agora") Instant agora, @Param("limite") int limite);
}
