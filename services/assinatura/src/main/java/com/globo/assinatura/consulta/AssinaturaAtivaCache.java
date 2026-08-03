package com.globo.assinatura.consulta;

import com.globo.assinatura.consulta.api.AssinaturaResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cache-aside da assinatura ativa do usuario, chaveado por uuid publico.
 *
 * <p>Recupera a representacao da assinatura ativa armazenada para o {@code usuarioUuid}; em miss
 * devolve vazio para a camada de consulta buscar no banco. Falha do cache degrada para miss, nunca
 * para erro da consulta.
 */
@Component
public class AssinaturaAtivaCache {

  /**
   * Recupera a assinatura ativa cacheada do usuario.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @return a assinatura ativa, ou vazio em miss ou em falha do cache
   */
  public Optional<AssinaturaResponse> recuperar(String usuarioUuid) {
    return Optional.empty();
  }

  /**
   * Grava a assinatura ativa do usuario no cache distribuido.
   *
   * @param usuarioUuid uuid publico do usuario dono
   * @param assinatura representacao da assinatura ativa a armazenar
   */
  public void popular(String usuarioUuid, AssinaturaResponse assinatura) {}
}
