package com.globo.assinatura.outbox;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia dos eventos da outbox.
 *
 * <p>Expone operacoes de escrita e leitura sobre {@link OutboxEvent}.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {}
