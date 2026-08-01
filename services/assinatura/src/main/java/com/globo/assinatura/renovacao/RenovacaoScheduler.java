package com.globo.assinatura.renovacao;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Renovacao;
import com.globo.assinatura.assinatura.RenovacaoRepository;
import com.globo.assinatura.messaging.event.RenovacaoSolicitada;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Varre assinaturas com o ciclo vencido e dispara a renovacao automatica.
 *
 * <p>Seleciona assinaturas ativas com {@code proxima_renovacao_em <= hoje} sob {@code FOR UPDATE
 * SKIP LOCKED}, permitindo que multiplas instâncias processem lotes disjuntos. Para cada uma: se o
 * dono optou por nao renovar, cancela a assinatura; caso contrario, cria a renovacao do ciclo
 * (idempotente por ciclo) e grava {@code RenovacaoSolicitada} na outbox na mesma transacao,
 * mantendo o acesso liberado enquanto a cobranca e decidida.
 */
@Component
public class RenovacaoScheduler {

  private static final Logger log = LoggerFactory.getLogger(RenovacaoScheduler.class);
  private static final String AGGREGATE_TYPE = "Renovacao";
  private static final String EVENT_TYPE = "RenovacaoSolicitada";

  private final AssinaturaRepository assinaturaRepository;
  private final RenovacaoRepository renovacaoRepository;
  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;
  private final int tamanhoLote;

  /**
   * Constroi o scheduler com os repositorios, o serializador JSON e o tamanho do lote injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON do evento de dominio
   * @param tamanhoLote maximo de assinaturas processadas por ciclo
   */
  public RenovacaoScheduler(
      AssinaturaRepository assinaturaRepository,
      RenovacaoRepository renovacaoRepository,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper,
      @Value("${app.renovacao.tamanho-lote}") int tamanhoLote) {
    this.assinaturaRepository = assinaturaRepository;
    this.renovacaoRepository = renovacaoRepository;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
    this.tamanhoLote = tamanhoLote;
  }

  /**
   * Processa as assinaturas vencidas, criando renovacoes e eventos de outbox numa transacao unica.
   */
  @Scheduled(fixedDelayString = "${app.renovacao.intervalo-ms}")
  @Transactional
  public void varrerVencimentos() {
    List<Assinatura> vencidas =
        assinaturaRepository.buscarVencidasParaRenovacao(LocalDate.now(), tamanhoLote);
    for (Assinatura assinatura : vencidas) {
      processar(assinatura);
    }
  }

  private void processar(Assinatura assinatura) {
    if (!assinatura.isRenovacaoAutomatica()) {
      assinatura.cancelar();
      log.info("Assinatura {} cancelada por opt-out no vencimento", assinatura.getId());
      return;
    }
    LocalDate cicloReferencia = assinatura.getFimCiclo();
    if (renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(
        assinatura.getId(), cicloReferencia)) {
      return;
    }
    int numeroCiclo = (int) renovacaoRepository.countByAssinaturaId(assinatura.getId()) + 1;
    Renovacao renovacao =
        renovacaoRepository.save(new Renovacao(assinatura.getId(), cicloReferencia, numeroCiclo));
    assinatura.iniciarRenovacao();
    gravarEvento(assinatura, renovacao);
    log.info(
        "Renovacao {} criada para a assinatura {} no ciclo {}",
        renovacao.getUuid(),
        assinatura.getId(),
        numeroCiclo);
  }

  private void gravarEvento(Assinatura assinatura, Renovacao renovacao) {
    RenovacaoSolicitada evento =
        new RenovacaoSolicitada(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(renovacao.getUuid()),
            UUID.fromString(assinatura.getUuid()),
            assinatura.getPlano(),
            assinatura.getPlano().valor(),
            renovacao.getNumeroCiclo());
    outboxRepository.save(
        OutboxEvent.criar(
            evento.eventId(),
            AGGREGATE_TYPE,
            evento.renovacaoId(),
            EVENT_TYPE,
            serializar(evento)));
  }

  private String serializar(RenovacaoSolicitada evento) {
    try {
      return jsonMapper.writeValueAsString(evento);
    } catch (JacksonException e) {
      throw new IllegalStateException("Falha ao serializar evento RenovacaoSolicitada", e);
    }
  }
}
