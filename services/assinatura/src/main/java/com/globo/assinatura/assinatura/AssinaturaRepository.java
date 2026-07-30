package com.globo.assinatura.assinatura;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Assinatura}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface AssinaturaRepository extends JpaRepository<Assinatura, Long> {}
