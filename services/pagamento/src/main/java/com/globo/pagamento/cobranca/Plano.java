package com.globo.pagamento.cobranca;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Planos de assinatura disponiveis. */
public enum Plano {
  BASICO,
  PREMIUM,
  FAMILIA;

  /**
   * Desserializa o plano a partir do nome, retornando {@code null} quando o valor nao corresponde a
   * um plano suportado.
   *
   * <p>O {@code null} deixa o campo para a validacao de obrigatoriedade flaggar, produzindo um erro
   * com o nome do campo em vez de um erro de desserializacao opaco.
   *
   * @param valor nome do plano vindo do JSON
   * @return o plano correspondente, ou {@code null} se o valor for invalido
   */
  @JsonCreator
  public static Plano from(String valor) {
    if (valor == null) {
      return null;
    }
    for (Plano plano : values()) {
      if (plano.name().equals(valor)) {
        return plano;
      }
    }
    return null;
  }
}
