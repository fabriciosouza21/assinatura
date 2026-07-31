package com.globo.assinatura.assinatura;

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
 * Representa a assinatura de um usuario.
 *
 * <p>Agregado de persistencia que armazena o plano escolhido, o usuario dono pelo identificador
 * interno, o status do ciclo de vida e as datas de vigencia.
 */
@Entity
@Table(name = "assinatura")
public class Assinatura {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String uuid;

  private Long usuarioId;

  @Enumerated(EnumType.STRING)
  private Plano plano;

  private LocalDate dataInicio;

  private LocalDate dataExpiracao;

  @Enumerated(EnumType.STRING)
  private StatusAssinatura status;

  private LocalDate inicioCiclo;

  private LocalDate fimCiclo;

  private LocalDate proximaRenovacaoEm;

  private boolean renovacaoAutomatica;

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected Assinatura() {}

  /**
   * Cria uma assinatura para o usuario e o plano informados.
   *
   * <p>Nasce em {@link StatusAssinatura#AGUARDANDO_PAGAMENTO}, com uuid publico gerado e datas de
   * vigencia nulas.
   *
   * @param usuarioId identificador interno do usuario dono
   * @param plano plano contratado
   */
  public Assinatura(Long usuarioId, Plano plano) {
    this.uuid = UUID.randomUUID().toString();
    this.usuarioId = usuarioId;
    this.plano = plano;
    this.status = StatusAssinatura.AGUARDANDO_PAGAMENTO;
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
   * Retorna o uuid publico da assinatura.
   *
   * @return uuid publico
   */
  public String getUuid() {
    return uuid;
  }

  /**
   * Retorna o identificador interno do usuario dono.
   *
   * @return identificador interno do usuario
   */
  public Long getUsuarioId() {
    return usuarioId;
  }

  /**
   * Retorna o plano contratado.
   *
   * @return plano contratado
   */
  public Plano getPlano() {
    return plano;
  }

  /**
   * Retorna a data de inicio da vigencia.
   *
   * @return data de inicio, ou {@code null} enquanto a assinatura aguarda pagamento
   */
  public LocalDate getDataInicio() {
    return dataInicio;
  }

  /**
   * Retorna a data de expiracao da vigencia.
   *
   * @return data de expiracao, ou {@code null} enquanto a assinatura aguarda pagamento
   */
  public LocalDate getDataExpiracao() {
    return dataExpiracao;
  }

  /**
   * Retorna o status do ciclo de vida.
   *
   * @return status da assinatura
   */
  public StatusAssinatura getStatus() {
    return status;
  }

  /**
   * Retorna o inicio do ciclo de renovacao corrente.
   *
   * @return data de inicio do ciclo, ou {@code null} antes da primeira ativacao
   */
  public LocalDate getInicioCiclo() {
    return inicioCiclo;
  }

  /**
   * Retorna o fim do ciclo de renovacao corrente.
   *
   * @return data de fim do ciclo, ou {@code null} antes da primeira ativacao
   */
  public LocalDate getFimCiclo() {
    return fimCiclo;
  }

  /**
   * Retorna a data da proxima renovacao.
   *
   * @return data da proxima renovacao, ou {@code null} antes da primeira ativacao
   */
  public LocalDate getProximaRenovacaoEm() {
    return proximaRenovacaoEm;
  }

  /**
   * Indica se a renovacao automatica esta habilitada.
   *
   * @return {@code true} se a renovacao automatica estiver habilitada
   */
  public boolean isRenovacaoAutomatica() {
    return renovacaoAutomatica;
  }

  /**
   * Transita o ciclo de vida para ativa apos a aprovacao do pagamento.
   *
   * <p>Apenas assinaturas aguardando pagamento podem ser ativadas. Eventos de aprovacao tardios ou
   * duplicados para uma assinatura ja resolvida sao ignorados, preservando a vigencia original,
   * conforme o comportamento simetrico de {@link #recusarPagamento()}.
   *
   * @param dataInicio data de inicio da vigencia
   * @param dataExpiracao data de expiracao da vigencia
   */
  public void ativar(LocalDate dataInicio, LocalDate dataExpiracao) {
    if (this.status != StatusAssinatura.AGUARDANDO_PAGAMENTO) {
      return;
    }
    this.status = StatusAssinatura.ATIVA;
    this.dataInicio = dataInicio;
    this.dataExpiracao = dataExpiracao;
    this.inicioCiclo = dataInicio;
    this.fimCiclo = dataExpiracao;
    this.proximaRenovacaoEm = dataExpiracao;
    this.renovacaoAutomatica = true;
  }

  /** Transita o ciclo de vida para pagamento recusado apos a reprovacao do pagamento. */
  public void recusarPagamento() {
    if (this.status != StatusAssinatura.AGUARDANDO_PAGAMENTO) {
      return;
    }
    this.status = StatusAssinatura.PAGAMENTO_RECUSADO;
  }

  /**
   * Avanca o ciclo de renovacao para o proximo periodo.
   *
   * @param novoFimCiclo data de fim do novo ciclo
   */
  public void renovar(LocalDate novoFimCiclo) {
    this.inicioCiclo = this.fimCiclo;
  }
}
