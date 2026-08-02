package com.globo.assinatura.renovacao;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link RenovacaoEventoProcessado}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface RenovacaoEventoProcessadoRepository
    extends JpaRepository<RenovacaoEventoProcessado, Long> {

  /**
   * Verifica se o resultado de uma renovacao ja foi processado.
   *
   * @param eventId identificador unico do evento recebido do barramento de mensagens
   * @return {@code true} se o evento ja tiver sido processado; {@code false} caso contrario
   */
  boolean existsByEventId(UUID eventId);
}
