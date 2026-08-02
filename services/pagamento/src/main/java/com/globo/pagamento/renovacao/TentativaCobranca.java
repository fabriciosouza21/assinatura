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
 * scheduler que cobra a tentativa no gateway preenche esses campos.
 *
 * <p>A construcao e responsabilidade de {@link PagamentoRenovacao}, que gera a sequencia de {@code
 * numero}. As transicoes de status sao decididas pelo webhook a partir do status oficial do
 * gateway.
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
   * Cria uma tentativa de uma renovacao, sempre {@link StatusTentativa#PENDENTE}.
   *
   * <p>Visibilidade restrita ao pacote: a sequencia de {@code numero} e gerada por {@link
   * PagamentoRenovacao#registrarTentativa()} e {@link
   * PagamentoRenovacao#registrarTentativa(TentativaCobranca, Instant)}, nunca informada a mao.
   *
   * @param renovacaoId identificador publico da renovacao; nao pode ser nulo nem vazio
   * @param numero numero ordinal da tentativa; deve ser maior ou igual a 1
   * @throws IllegalArgumentException se {@code renovacaoId} for vazio ou {@code numero} for menor
   *     que 1
   */
  TentativaCobranca(String renovacaoId, int numero) {
    if (renovacaoId == null || renovacaoId.isBlank()) {
      throw new IllegalArgumentException("renovacaoId nao pode ser vazio");
    }
    if (numero < 1) {
      throw new IllegalArgumentException("numero deve ser maior ou igual a 1");
    }
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
   * Registra o identificador da cobranca criada no gateway de pagamento.
   *
   * @param paymentId identificador da cobranca no gateway
   */
  public void registrarCobranca(String paymentId) {
    this.paymentId = paymentId;
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
   * Informa se a tentativa ainda aguarda decisao do gateway.
   *
   * <p>Uma tentativa ja decidida nao pode ser decidida de novo: e o guarda que absorve o reprocesso
   * de um webhook cuja decisao anterior foi persistida.
   *
   * @return {@code true} enquanto o status for {@link StatusTentativa#PENDENTE}
   */
  public boolean estaPendente() {
    return status == StatusTentativa.PENDENTE;
  }

  /**
   * Marca a tentativa como aprovada pelo gateway, encerrando as tentativas da renovacao.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void aprovar() {
    exigirPendente();
    this.status = StatusTentativa.APROVADA;
  }

  /**
   * Marca a tentativa como recusada pelo gateway, dando lugar a uma nova tentativa agendada.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void recusar() {
    exigirPendente();
    this.status = StatusTentativa.RECUSADA;
  }

  /**
   * Cancela a tentativa antes de uma decisao do gateway.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void cancelar() {
    exigirPendente();
    this.status = StatusTentativa.CANCELADA;
  }

  /**
   * Marca a tentativa como a recusa que esgota o ciclo, sem nova tentativa.
   *
   * @throws IllegalStateException se a tentativa ja tiver sido decidida
   */
  public void esgotar() {
    exigirPendente();
    this.status = StatusTentativa.TENTATIVAS_ESGOTADA;
  }

  /**
   * Agenda o instante a partir do qual a tentativa fica elegivel para cobranca.
   *
   * @param instante instante da cobranca; nao pode ser nulo
   * @throws IllegalArgumentException se {@code instante} for nulo
   */
  void agendarPara(Instant instante) {
    if (instante == null) {
      throw new IllegalArgumentException("proximaTentativaEm nao pode ser nulo");
    }
    this.proximaTentativaEm = instante;
  }

  private void exigirPendente() {
    if (!estaPendente()) {
      throw new IllegalStateException("tentativa ja decidida: " + status);
    }
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
