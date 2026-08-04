package com.globo.assinatura.renovacao;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Representa a renovacao de um ciclo de uma assinatura.
 *
 * <p>Cada ciclo vencido gera uma renovacao, vinculada a assinatura dona pelo identificador interno
 * e referenciando o ciclo pelo fim do periodo renovado. O {@code uuid} publico identifica a
 * renovacao entre servicos (chave de idempotencia do evento {@code RenovacaoSolicitada}) e o {@code
 * numeroCiclo} e o ordinal do ciclo dentro da assinatura.
 */
@Entity
@Table(name = "renovacao")
public class Renovacao {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String uuid;

  private Long assinaturaId;

  private LocalDate cicloReferencia;

  private int numeroCiclo;

  @Enumerated(EnumType.STRING)
  private StatusRenovacao status;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected Renovacao() {}

  /**
   * Cria uma renovacao para a assinatura e o ciclo informados.
   *
   * <p>Nasce em {@link StatusRenovacao#PENDENTE}, aguardando o resultado da cobranca, com uuid
   * publico gerado.
   *
   * @param assinaturaId identificador interno da assinatura dona
   * @param cicloReferencia fim do ciclo que esta sendo renovado
   * @param numeroCiclo ordinal do ciclo dentro da assinatura
   */
  public Renovacao(Long assinaturaId, LocalDate cicloReferencia, int numeroCiclo) {
    this.uuid = UUID.randomUUID().toString();
    this.assinaturaId = assinaturaId;
    this.cicloReferencia = cicloReferencia;
    this.numeroCiclo = numeroCiclo;
    this.status = StatusRenovacao.PENDENTE;
  }

  /**
   * Retorna o identificador tecnico gerado pelo banco de dados.
   *
   * @return identificador tecnico, ou {@code null} antes da persistencia
   */
  public Long getId() {
    return id;
  }

  /**
   * Retorna o uuid publico da renovacao.
   *
   * @return uuid publico
   */
  public String getUuid() {
    return uuid;
  }

  /**
   * Retorna o identificador interno da assinatura dona.
   *
   * @return identificador interno da assinatura
   */
  public Long getAssinaturaId() {
    return assinaturaId;
  }

  /**
   * Retorna o fim do ciclo que esta sendo renovado.
   *
   * @return fim do ciclo renovado
   */
  public LocalDate getCicloReferencia() {
    return cicloReferencia;
  }

  /**
   * Retorna o ordinal do ciclo dentro da assinatura.
   *
   * @return numero do ciclo
   */
  public int getNumeroCiclo() {
    return numeroCiclo;
  }

  /**
   * Retorna o status do ciclo de vida.
   *
   * @return status da renovacao
   */
  public StatusRenovacao getStatus() {
    return status;
  }

  /**
   * Marca a renovacao como aprovada apos a confirmacao da cobranca.
   *
   * @throws IllegalStateException se a renovacao ja estiver aprovada
   */
  public void aprovar() {
    if (this.status == StatusRenovacao.APROVADA) {
      throw new IllegalStateException("renovacao ja aprovada");
    }
    this.status = StatusRenovacao.APROVADA;
  }

  /**
   * Marca a renovacao com tentativas de cobranca esgotadas.
   *
   * @throws IllegalStateException se a renovacao ja estiver com tentativas esgotadas
   */
  public void esgotarTentativas() {
    if (this.status == StatusRenovacao.TENTATIVAS_ESGOTADA) {
      throw new IllegalStateException("renovacao ja com tentativas esgotadas");
    }
    this.status = StatusRenovacao.TENTATIVAS_ESGOTADA;
  }
}
