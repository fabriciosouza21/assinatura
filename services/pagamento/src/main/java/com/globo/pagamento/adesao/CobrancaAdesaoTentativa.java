package com.globo.pagamento.adesao;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tentativa de cobranca de uma adesao.
 *
 * <p>Uma linha por adesao, identificada por {@code assinaturaUuid}. A tentativa nasce {@link
 * StatusTentativaAdesao#PENDENTE}, sem {@code paymentId} e sem {@code proximaTentativaEm}: o
 * scheduler que cobra a tentativa no gateway preenche esses campos.
 *
 * <p>Falhas tecnicas do gateway (indisponibilidade, timeout) nao decidem a tentativa, mas ficam
 * contabilizadas em {@code falhasTecnicas}: o scheduler zera o contador quando o gateway se
 * recupera e a cobranca e criada, e esgota a tentativa quando o teto configurado e atingido.
 */
@Entity
@Table(name = "cobranca_adesao_tentativa")
public class CobrancaAdesaoTentativa {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String assinaturaUuid;

  private BigDecimal valor;

  @Enumerated(EnumType.STRING)
  private StatusTentativaAdesao status;

  private String paymentId;

  private int falhasTecnicas;

  private Instant proximaTentativaEm;

  private Instant criadoEm;

  private Instant atualizadoEm;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected CobrancaAdesaoTentativa() {}

  /**
   * Cria uma tentativa de adesao, sempre {@link StatusTentativaAdesao#PENDENTE}.
   *
   * @param assinaturaUuid identificador publico da assinatura; nao pode ser nulo nem vazio
   * @param valor valor da adesao; nao pode ser nulo nem menor ou igual a zero
   * @throws IllegalArgumentException se {@code assinaturaUuid} for vazio ou {@code valor} for
   *     invalido
   */
  public CobrancaAdesaoTentativa(String assinaturaUuid, BigDecimal valor) {
    if (assinaturaUuid == null || assinaturaUuid.isBlank()) {
      throw new IllegalArgumentException("assinaturaUuid nao pode ser vazio");
    }
    if (valor == null || valor.signum() <= 0) {
      throw new IllegalArgumentException("valor deve ser positivo");
    }
    this.assinaturaUuid = assinaturaUuid;
    this.valor = valor;
    this.status = StatusTentativaAdesao.PENDENTE;
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
   * Retorna o identificador publico da assinatura.
   *
   * @return uuid da assinatura
   */
  public String getAssinaturaUuid() {
    return assinaturaUuid;
  }

  /**
   * Retorna o valor da adesao a ser cobrado.
   *
   * @return valor da adesao
   */
  public BigDecimal getValor() {
    return valor;
  }

  /**
   * Retorna o status da tentativa.
   *
   * @return status da tentativa
   */
  public StatusTentativaAdesao getStatus() {
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
   * Retorna o numero de falhas tecnicas consecutivas do gateway nesta tentativa.
   *
   * @return contador de falhas tecnicas consecutivas
   */
  public int getFalhasTecnicas() {
    return falhasTecnicas;
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

  /**
   * Contabiliza uma falha tecnica do gateway nesta tentativa.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void registrarFalhaTecnica() {
    exigirPendente();
    this.falhasTecnicas++;
  }

  /**
   * Informa se o teto de falhas tecnicas configurado foi atingido.
   *
   * @param teto numero de falhas tecnicas consecutivas que esgota a tentativa; deve ser maior que 0
   * @return {@code true} quando o contador alcanca ou ultrapassa o teto
   * @throws IllegalArgumentException se {@code teto} for menor ou igual a 0
   */
  public boolean esgotouFalhasTecnicas(int teto) {
    if (teto < 1) {
      throw new IllegalArgumentException("teto deve ser maior que 0");
    }
    return falhasTecnicas >= teto;
  }

  /**
   * Marca a tentativa como esgotada, sem nova tentativa.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void esgotar() {
    exigirPendente();
    this.status = StatusTentativaAdesao.ESGOTADA;
  }

  /**
   * Agenda o instante a partir do qual a tentativa fica elegivel para cobranca.
   *
   * @param instante instante da cobranca; nao pode ser nulo
   * @throws IllegalArgumentException se {@code instante} for nulo
   */
  public void agendarPara(Instant instante) {
    if (instante == null) {
      throw new IllegalArgumentException("proximaTentativaEm nao pode ser nulo");
    }
    this.proximaTentativaEm = instante;
  }

  /**
   * Registra o identificador da cobranca criada no gateway de pagamento.
   *
   * <p>Uma cobranca criada significa que o gateway se recuperou das falhas tecnicas: o contador
   * {@code falhasTecnicas} e zerado e o status transita para {@link StatusTentativaAdesao#COBRADA},
   * aguardando a decisao final do gateway via webhook.
   *
   * @param paymentId identificador da cobranca no gateway
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void registrarCobranca(String paymentId) {
    exigirPendente();
    this.paymentId = paymentId;
    this.falhasTecnicas = 0;
    this.status = StatusTentativaAdesao.COBRADA;
  }

  private void exigirPendente() {
    if (status != StatusTentativaAdesao.PENDENTE) {
      throw new IllegalStateException("tentativa ja decidida: " + status);
    }
  }

  /** Preenche os instantes de criacao e atualizacao antes do primeiro persist. */
  @PrePersist
  void aoPersistir() {
    Instant agora = Instant.now();
    this.criadoEm = agora;
    this.atualizadoEm = agora;
  }
}
