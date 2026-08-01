package com.globo.assinatura.assinatura;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Renovacao}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface RenovacaoRepository extends JpaRepository<Renovacao, Long> {}
