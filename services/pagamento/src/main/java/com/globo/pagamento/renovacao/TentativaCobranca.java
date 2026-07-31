package com.globo.pagamento.renovacao;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Tentativa de cobranca de uma renovacao.
 *
 * <p>Uma linha por tentativa, identificada por {@code (renovacaoId, numero)}. A tentativa inicial
 * nasce {@link StatusTentativa#PENDENTE}, sem {@code paymentId} e sem {@code proximaTentativaEm}: o
 * scheduler que efetivamente chama o gateway (BE-12) preenche esses campos quando a cobra.
 */
@Entity
@Table(name = "tentativa_cobranca")
public class TentativaCobranca {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String renovacaoId;

  private int numero;

  @Enumerated(EnumType.STRING)
  private StatusTentativa status;

  private String paymentId;

  private Instant proximaTentativaEm;

  private Instant criadoEm;

  private Instant atualizadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected TentativaCobranca() {}

  /**
   * Cria a tentativa inicial de uma renovacao, sempre {@link StatusTentativa#PENDENTE}.
   *
   * @param renovacaoId identificador publico da renovacao
   * @param numero numero ordinal da tentativa (1 para a primeira)
   */
  public TentativaCobranca(String renovacaoId, int numero) {
    this.renovacaoId = renovacaoId;
    this.numero = numero;
    this.status = StatusTentativa.PENDENTE;
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
   * Retorna o identificador publico da renovacao.
   *
   * @return uuid da renovacao
   */
  public String getRenovacaoId() {
    return renovacaoId;
  }

  /**
   * Retorna o numero ordinal da tentativa.
   *
   * @return numero da tentativa (1 para a primeira)
   */
  public int getNumero() {
    return numero;
  }

  /**
   * Retorna o status da tentativa.
   *
   * @return status da tentativa
   */
  public StatusTentativa getStatus() {
    return status;
  }

  /**
   * Retorna o identificador da cobranca no gateway.
   *
   * @return payment id, ou {@code null} enquanto o gateway nao e chamado
   */
  public String getPaymentId() {
    return paymentId;
  }

  /**
   * Retorna o instante em que a tentativa deve ser cobrada.
   *
   * @return instante da proxima tentativa, ou {@code null} enquanto nao agendada
   */
  public Instant getProximaTentativaEm() {
    return proximaTentativaEm;
  }

  /**
   * Retorna o instante de criacao do registro.
   *
   * @return instante de criacao, ou {@code null} antes da persistencia
   */
  public Instant getCriadoEm() {
    return criadoEm;
  }

  /**
   * Retorna o instante da ultima atualizacao do registro.
   *
   * @return instante da ultima atualizacao, ou {@code null} antes da persistencia
   */
  public Instant getAtualizadoEm() {
    return atualizadoEm;
  }

  /** Preenche os instantes de criacao e atualizacao antes do primeiro persist. */
  @PrePersist
  void aoPersistir() {
    Instant agora = Instant.now();
    this.criadoEm = agora;
    this.atualizadoEm = agora;
  }
}
