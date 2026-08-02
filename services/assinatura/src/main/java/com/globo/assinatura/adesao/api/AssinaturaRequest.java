package com.globo.assinatura.adesao.api;

import com.globo.assinatura.assinatura.Plano;
import jakarta.validation.constraints.NotNull;

/**
 * Dados enviados na solicitacao de uma assinatura.
 *
 * <p>O dono da assinatura nao vem no corpo: e derivado do token JWT da requisicao, impedindo que um
 * usuario autenticado assine em nome de outro.
 *
 * @param plano plano contratado; nao pode ser nulo
 */
public record AssinaturaRequest(@NotNull Plano plano) {}
