package com.globo.pagamento.cobranca;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Correlacao entre uma assinatura e a cobranca criada no gateway de pagamento.
 *
 * <p>Agregado de persistencia que registra o {@code assinaturaUuid} (chave de idempotencia) e o
 * {@code paymentId} retornado pelo gateway, com o status da cobranca.
 */
@Entity
@Table(name = "cobranca")
public class Cobranca {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String assinaturaUuid;

  private String paymentId;

  @Enumerated(EnumType.STRING)
  private StatusCobranca status;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected Cobranca() {}

  /**
   * Cria uma correlacao de cobranca para a assinatura informada.
   *
   * @param assinaturaUuid identificador publico da assinatura
   * @param paymentId identificador da cobranca no gateway
   * @param status status inicial da cobranca
   */
  public Cobranca(String assinaturaUuid, String paymentId, StatusCobranca status) {
    this.assinaturaUuid = assinaturaUuid;
    this.paymentId = paymentId;
    this.status = status;
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
   * Retorna o identificador publico da assinatura correlacionada.
   *
   * @return uuid da assinatura
   */
  public String getAssinaturaUuid() {
    return assinaturaUuid;
  }

  /**
   * Retorna o identificador da cobranca no gateway.
   *
   * @return payment id da cobranca
   */
  public String getPaymentId() {
    return paymentId;
  }

  /**
   * Retorna o status da cobranca.
   *
   * @return status da cobranca
   */
  public StatusCobranca getStatus() {
    return status;
  }
}
