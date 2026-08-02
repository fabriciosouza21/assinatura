package com.globo.assinatura.renovacao.agendador;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.renovacao.Renovacao;
import com.globo.assinatura.renovacao.RenovacaoRepository;
import com.globo.assinatura.shared.contrato.RenovacaoSolicitada;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import java.time.Clock;
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
  private final Clock clock;

  /**
   * Constroi o scheduler com os repositorios, o serializador JSON, o tamanho do lote e o relogio
   * injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON do evento de dominio
   * @param tamanhoLote maximo de assinaturas processadas por ciclo
   * @param clock relogio para calculo da data de vencimento e do instante do evento
   */
  public RenovacaoScheduler(
      AssinaturaRepository assinaturaRepository,
      RenovacaoRepository renovacaoRepository,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper,
      @Value("${app.renovacao.tamanho-lote}") int tamanhoLote,
      Clock clock) {
    this.assinaturaRepository = assinaturaRepository;
    this.renovacaoRepository = renovacaoRepository;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
    this.tamanhoLote = tamanhoLote;
    this.clock = clock;
  }

  /**
   * Processa as assinaturas vencidas, criando renovacoes e eventos de outbox numa transacao unica.
   *
   * <p>Uma assinatura cujo processamento falhe e isolada: o erro e registrado e o lote segue para
   * as demais, mantendo o fluxo de cobranca das assinaturas saudaveis mesmo diante de uma linha
   * problematicas. A assinatura falha permanece vencida e sera retomada no proximo ciclo.
   */
  @Scheduled(fixedDelayString = "${app.renovacao.intervalo-ms}")
  @Transactional
  public void varrerVencimentos() {
    List<Assinatura> vencidas =
        assinaturaRepository.buscarVencidasParaRenovacao(LocalDate.now(clock), tamanhoLote);
    log.atDebug()
        .addKeyValue("event", "renovacao_batch_inicio")
        .addKeyValue("tamanhoLote", vencidas.size())
        .log("Varredura de renovacoes iniciada");
    for (Assinatura assinatura : vencidas) {
      try {
        processar(assinatura);
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "renovacao_falha_isolada")
            .addKeyValue("assinaturaId", assinatura.getUuid())
            .addKeyValue("errorType", e.getClass().getSimpleName())
            .log("Falha ao processar assinatura vencida");
      }
    }
    log.atDebug()
        .addKeyValue("event", "renovacao_batch_fim")
        .addKeyValue("tamanhoLote", vencidas.size())
        .log("Varredura de renovacoes concluida");
  }

  private void processar(Assinatura assinatura) {
    if (!assinatura.isRenovacaoAutomatica()) {
      assinatura.cancelar();
      log.atInfo()
          .addKeyValue("event", "assinatura_cancelada_opt_out")
          .addKeyValue("assinaturaId", assinatura.getUuid())
          .log("Assinatura cancelada por opt-out no vencimento");
      return;
    }
    LocalDate cicloReferencia = assinatura.getFimCiclo();
    if (renovacaoRepository.existsByAssinaturaIdAndCicloReferencia(
        assinatura.getId(), cicloReferencia)) {
      log.atDebug()
          .addKeyValue("event", "renovacao_deduplicada_ciclo")
          .addKeyValue("assinaturaId", assinatura.getUuid())
          .log("Renovacao do ciclo ja iniciada");
      return;
    }
    int numeroCiclo = (int) renovacaoRepository.countByAssinaturaId(assinatura.getId()) + 1;
    Renovacao renovacao =
        renovacaoRepository.save(new Renovacao(assinatura.getId(), cicloReferencia, numeroCiclo));
    assinatura.iniciarRenovacao();
    gravarEvento(assinatura, renovacao);
    log.atInfo()
        .addKeyValue("event", "renovacao_criada")
        .addKeyValue("renovacaoId", renovacao.getUuid())
        .addKeyValue("assinaturaId", assinatura.getUuid())
        .addKeyValue("ciclo", numeroCiclo)
        .log("Renovacao criada");
  }

  private void gravarEvento(Assinatura assinatura, Renovacao renovacao) {
    RenovacaoSolicitada evento =
        new RenovacaoSolicitada(
            UUID.randomUUID(),
            Instant.now(clock),
            UUID.fromString(renovacao.getUuid()),
            UUID.fromString(assinatura.getUuid()),
            com.globo.assinatura.shared.contrato.Plano.valueOf(assinatura.getPlano().name()),
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
