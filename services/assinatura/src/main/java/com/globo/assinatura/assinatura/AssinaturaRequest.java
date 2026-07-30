package com.globo.assinatura.assinatura;

import jakarta.validation.constraints.NotBlank;

/**
 * Dados enviados na solicitacao de uma assinatura.
 *
 * @param usuarioId uuid publico do usuario que solicita a assinatura; nao pode ser vazio
 * @param plano plano contratado
 */
public record AssinaturaRequest(@NotBlank String usuarioId, Plano plano) {}
