package com.globo.assinatura.shared.web;

import java.util.List;

/**
 * Detalhe de erro no formato RFC 7807/9457.
 *
 * <p>No erro 400, o array {@link FieldError} lista os campos invalidos para o cliente corrigir a
 * requisicao.
 *
 * @param status codigo de status HTTP
 * @param title titulo curto do erro
 * @param errors campos invalidos, quando aplicavel
 */
public record Problem(int status, String title, List<FieldError> errors) {

  /**
   * Erro de um campo especifico da requisicao.
   *
   * @param campo nome do campo invalido
   * @param motivo motivo da invalidade
   */
  public record FieldError(String campo, String motivo) {}
}
