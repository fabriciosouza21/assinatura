package com.globo.pagamento.renovacao;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link TentativaCobranca}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface TentativaCobrancaRepository extends JpaRepository<TentativaCobranca, Long> {}
