package com.globo.assinatura.assinatura;

import java.time.LocalDate;

/**
 * Representa a renovacao de um ciclo de uma assinatura.
 *
 * <p>Cada ciclo vencido gera uma renovacao, vinculada a assinatura dona pelo identificador interno
 * e referenciando o ciclo pelo fim do periodo renovado.
 */
public class Renovacao {

  private Long assinaturaId;

  private LocalDate cicloReferencia;

  private StatusRenovacao status;

  /**
   * Cria uma renovacao para a assinatura e o ciclo informados.
   *
   * @param assinaturaId identificador interno da assinatura dona
   * @param cicloReferencia fim do ciclo que esta sendo renovado
   */
  public Renovacao(Long assinaturaId, LocalDate cicloReferencia) {
    this.assinaturaId = assinaturaId;
    this.cicloReferencia = cicloReferencia;
    this.status = StatusRenovacao.PENDENTE;
  }

  /**
   * Retorna o status do ciclo de vida.
   *
   * @return status da renovacao
   */
  public StatusRenovacao getStatus() {
    return status;
  }

  /** Marca a renovacao como aprovada apos a confirmacao da cobranca. */
  public void aprovar() {
    if (this.status == StatusRenovacao.APROVADA) {
      throw new IllegalStateException("renovacao ja aprovada");
    }
    this.status = StatusRenovacao.APROVADA;
  }

  /** Marca a renovacao com tentativas de cobranca esgotadas. */
  public void esgotarTentativas() {
    this.status = StatusRenovacao.TENTATIVAS_ESGOTADA;
  }
}
