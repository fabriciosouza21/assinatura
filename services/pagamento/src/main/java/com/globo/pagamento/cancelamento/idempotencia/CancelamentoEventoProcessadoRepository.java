package com.globo.pagamento.cancelamento.idempotencia;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de persistencia dos eventos de cancelamento ja processados. */
public interface CancelamentoEventoProcessadoRepository
    extends JpaRepository<CancelamentoEventoProcessado, Long> {

  /**
   * Registra o evento somente quando seu identificador ainda nao tiver sido processado.
   *
   * @param eventId identificador unico do evento
   * @param assinaturaId identificador publico da assinatura do evento
   * @return {@code 1} se a reserva foi criada; {@code 0} se o evento ja existia
   */
  @Modifying
  @Query(
      value =
          "insert into cancelamento_evento_processado "
              + "(event_id, assinatura_id, processado_em) values "
              + "(:eventId, :assinaturaId, current_timestamp) on conflict (event_id) do nothing",
      nativeQuery = true)
  int registrarSeNovo(@Param("eventId") UUID eventId, @Param("assinaturaId") UUID assinaturaId);
}
