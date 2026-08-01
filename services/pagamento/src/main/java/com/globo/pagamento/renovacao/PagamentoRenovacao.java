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
   * <p>No caminho de escrita em producao a insercao usa {@link
   * PagamentoRenovacaoRepository#inserirSeNaoExistir}, uma query nativa que nao passa por este
   * construtor; as mesmas invariantes validadas aqui ja sao aplicadas antes disso por {@code
   * RenovacaoSolicitadaConsumer.validar}. Este construtor valida do mesmo jeito para nao deixar um
   * caminho de construcao direta (teste, ou uma futura migracao para {@code repository.save(...)})
   * sem defesa.
   *
   * @param renovacaoId identificador publico da renovacao (chave de idempotencia); nao pode ser
   *     nulo nem vazio
   * @param assinaturaId identificador publico da assinatura renovada; nao pode ser nulo nem vazio
   * @param plano plano contratado; nao pode ser nulo
   * @param valor valor mensal em reais; deve ser positivo
   * @param cicloReferencia numero do ciclo da renovacao; deve ser maior ou igual a 1
   * @throws IllegalArgumentException se algum campo obrigatorio estiver ausente ou invalido
   */
  public PagamentoRenovacao(
      String renovacaoId, String assinaturaId, Plano plano, BigDecimal valor, int cicloReferencia) {
    if (renovacaoId == null || renovacaoId.isBlank()) {
      throw new IllegalArgumentException("renovacaoId nao pode ser vazio");
    }
    if (assinaturaId == null || assinaturaId.isBlank()) {
      throw new IllegalArgumentException("assinaturaId nao pode ser vazio");
    }
    if (plano == null) {
      throw new IllegalArgumentException("plano nao pode ser nulo");
    }
    if (valor == null || valor.signum() <= 0) {
      throw new IllegalArgumentException("valor deve ser positivo");
    }
    if (cicloReferencia < 1) {
      throw new IllegalArgumentException("cicloReferencia deve ser maior ou igual a 1");
    }
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
