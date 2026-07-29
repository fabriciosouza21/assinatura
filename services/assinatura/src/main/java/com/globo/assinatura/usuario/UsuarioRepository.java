package com.globo.assinatura.usuario;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio de persistencia do agregado {@link Usuario}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD sem metodos custom
 * adicionais nesta iteracao.
 */
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {}
