package com.globo.pagamento.renovacao;

import com.globo.pagamento.cobranca.Plano;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pagamento de uma renovacao de assinatura.
 *
 * <p>Agregado de persistencia que registra o pedido de renovacao recebido de {@code
 * RenovacaoSolicitada}. A unicidade por {@code renovacaoId} e a garantia de idempotencia: um
 * redelivery nao cria um segundo pagamento para a mesma renovacao.
 */
@Entity
@Table(name = "pagamento_renovacao")
public class PagamentoRenovacao {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String renovacaoId;

  private String assinaturaId;

  @Enumerated(EnumType.STRING)
  private Plano plano;

  private BigDecimal valor;

  private int cicloReferencia;

  private Instant criadoEm;

  private Instant atualizadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected PagamentoRenovacao() {}

  /**
   * Cria um pagamento de renovacao a partir do evento {@code RenovacaoSolicitada}.
   *
   * @param renovacaoId identificador publico da renovacao (chave de idempotencia)
   * @param assinaturaId identificador publico da assinatura renovada
   * @param plano plano contratado
   * @param valor valor mensal em reais
   * @param cicloReferencia numero do ciclo da renovacao
   */
  public PagamentoRenovacao(
      String renovacaoId, String assinaturaId, Plano plano, BigDecimal valor, int cicloReferencia) {
    this.renovacaoId = renovacaoId;
    this.assinaturaId = assinaturaId;
    this.plano = plano;
    this.valor = valor;
    this.cicloReferencia = cicloReferencia;
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
   * Retorna o identificador publico da renovacao (chave de idempotencia).
   *
   * @return uuid da renovacao
   */
  public String getRenovacaoId() {
    return renovacaoId;
  }

  /**
   * Retorna o identificador publico da assinatura renovada.
   *
   * @return uuid da assinatura
   */
  public String getAssinaturaId() {
    return assinaturaId;
  }

  /**
   * Retorna o plano contratado.
   *
   * @return plano da renovacao
   */
  public Plano getPlano() {
    return plano;
  }

  /**
   * Retorna o valor mensal em reais.
   *
   * @return valor da renovacao
   */
  public BigDecimal getValor() {
    return valor;
  }

  /**
   * Retorna o numero do ciclo da renovacao.
   *
   * @return ciclo de referencia
   */
  public int getCicloReferencia() {
    return cicloReferencia;
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
}
