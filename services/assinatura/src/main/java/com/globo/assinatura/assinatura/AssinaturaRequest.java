package com.globo.assinatura.assinatura;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.UUID;

/**
 * Dados enviados na solicitacao de uma assinatura.
 *
 * @param usuarioId uuid publico do usuario que solicita a assinatura; nao pode ser vazio nem ter
 *     formato invalido
 * @param plano plano contratado; nao pode ser nulo
 */
public record AssinaturaRequest(@NotBlank @UUID String usuarioId, @NotNull Plano plano) {}
