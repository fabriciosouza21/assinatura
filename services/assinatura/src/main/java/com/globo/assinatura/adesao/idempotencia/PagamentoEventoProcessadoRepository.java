package com.globo.assinatura.adesao.idempotencia;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link PagamentoEventoProcessado}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface PagamentoEventoProcessadoRepository
    extends JpaRepository<PagamentoEventoProcessado, Long> {

  /**
   * Verifica se um evento de pagamento ja foi processado.
   *
   * @param eventId identificador unico do evento recebido do barramento de mensagens
   * @return {@code true} se o evento ja tiver sido processado; {@code false} caso contrario
   */
  boolean existsByEventId(UUID eventId);
}
