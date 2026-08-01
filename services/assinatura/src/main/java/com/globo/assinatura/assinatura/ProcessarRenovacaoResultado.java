package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.AssinaturaRenovada;
import com.globo.assinatura.messaging.event.AssinaturaSuspensa;
import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import com.globo.assinatura.messaging.event.RenovacaoTentativasEsgotadas;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.renovacao.RenovacaoEventoProcessadoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Command de processamento do resultado de uma renovacao.
 *
 * <p>Processa o evento de pagamento aprovado pela renovacao aplicando a transicao de dominio
 * correspondente na assinatura dona sob lock pessimista para garantir exclusao mutua entre entregas
 * concorrentes.
 */
@Service
public class ProcessarRenovacaoResultado {

  private static final String AGGREGATE_TYPE = "Renovacao";
  private static final String EVENT_TYPE = "AssinaturaRenovada";
  private static final String EVENT_TYPE_ESGOTADO = "AssinaturaSuspensa";

  private final RenovacaoRepository renovacaoRepository;
  private final AssinaturaRepository assinaturaRepository;
  private final RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository;
  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  /**
   * Constroi o command com os repositorios, a outbox, o serializador JSON e o relogio injetados.
   *
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param renovacaoEventoProcessadoRepository repositorio de idempotencia de eventos de renovacao
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON do evento de dominio
   * @param clock relogio para calculo do instante do evento
   */
  public ProcessarRenovacaoResultado(
      RenovacaoRepository renovacaoRepository,
      AssinaturaRepository assinaturaRepository,
      RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper,
      Clock clock) {
    this.renovacaoRepository = renovacaoRepository;
    this.assinaturaRepository = assinaturaRepository;
    this.renovacaoEventoProcessadoRepository = renovacaoEventoProcessadoRepository;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  /**
   * Processa o evento de renovacao aprovada atualizando a assinatura dona.
   *
   * <p>Eventos ja processados sao ignorados pelo identificador unico, sem acquire lock sobre a
   * renovacao. Eventos cuja renovacao ainda nao existe tambem sao ignorados, sem registrar
   * idempotencia, permitindo que o redelivery processe o evento quando a renovacao surgir. Entregas
   * tardias sobre uma renovacao ja resolvida (status diferente de PENDENTE) sao tratadas como no-op
   * idempotente. Caso contrario, carrega a renovacao sob lock pessimista pelo uuid publico, resolve
   * a assinatura dona pelo identificador interno, confirma a renovacao do ciclo e grava o evento
   * {@code AssinaturaRenovada} na outbox na mesma transacao.
   *
   * @param evento evento de pagamento de renovacao aprovado
   */
  @Transactional
  public void executar(PagamentoRenovacaoAprovado evento) {
    if (renovacaoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      return;
    }
    Optional<Renovacao> possivelRenovacao =
        renovacaoRepository.buscarPorUuidParaAtualizacao(evento.renovacaoId().toString());
    if (possivelRenovacao.isEmpty()) {
      return;
    }
    Renovacao renovacao = possivelRenovacao.get();
    if (renovacao.getStatus() != StatusRenovacao.PENDENTE) {
      return;
    }
    Assinatura assinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId()).orElseThrow();
    renovacao.aprovar();
    assinatura.renovar(assinatura.getFimCiclo().plusMonths(1));
    gravarEvento(assinatura, renovacao, evento);
  }

  /**
   * Processa o evento de tentativas esgotadas suspendendo a assinatura dona.
   *
   * <p>Segue o mesmo contrato de idempotencia e lock de {@link
   * #executar(PagamentoRenovacaoAprovado)}: eventos ja processados sao ignorados sem acquire lock;
   * eventos para renovacao inexistente sao ignorados sem registrar idempotencia; entregas tardias
   * sobre renovacao ja resolvida sao tratadas como no-op idempotente. Caso contrario, esgota as
   * tentativas da renovacao, suspende a assinatura dona e grava o evento {@code AssinaturaSuspensa}
   * na outbox na mesma transacao.
   *
   * @param evento evento de tentativas de renovacao esgotadas
   */
  @Transactional
  public void executar(RenovacaoTentativasEsgotadas evento) {
    if (renovacaoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      return;
    }
    Optional<Renovacao> possivelRenovacao =
        renovacaoRepository.buscarPorUuidParaAtualizacao(evento.renovacaoId().toString());
    if (possivelRenovacao.isEmpty()) {
      return;
    }
    Renovacao renovacao = possivelRenovacao.get();
    if (renovacao.getStatus() != StatusRenovacao.PENDENTE) {
      return;
    }
    Assinatura assinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId()).orElseThrow();
    renovacao.esgotarTentativas();
    assinatura.suspender();
    gravarEventoSuspensao(assinatura, renovacao);
  }

  private void gravarEventoSuspensao(Assinatura assinatura, Renovacao renovacao) {
    AssinaturaSuspensa eventoSaida =
        new AssinaturaSuspensa(
            UUID.randomUUID(),
            Instant.now(clock),
            UUID.fromString(assinatura.getUuid()),
            UUID.fromString(renovacao.getUuid()));
    outboxRepository.save(
        OutboxEvent.criar(
            eventoSaida.eventId(),
            AGGREGATE_TYPE,
            eventoSaida.renovacaoId(),
            EVENT_TYPE_ESGOTADO,
            serializarSuspensao(eventoSaida)));
  }

  private String serializarSuspensao(AssinaturaSuspensa evento) {
    try {
      return jsonMapper.writeValueAsString(evento);
    } catch (JacksonException e) {
      throw new IllegalStateException("Falha ao serializar evento AssinaturaSuspensa", e);
    }
  }

  private void gravarEvento(
      Assinatura assinatura, Renovacao renovacao, PagamentoRenovacaoAprovado evento) {
    AssinaturaRenovada eventoSaida =
        new AssinaturaRenovada(
            UUID.randomUUID(),
            Instant.now(clock),
            UUID.fromString(assinatura.getUuid()),
            UUID.fromString(renovacao.getUuid()),
            evento.paymentId());
    outboxRepository.save(
        OutboxEvent.criar(
            eventoSaida.eventId(),
            AGGREGATE_TYPE,
            eventoSaida.renovacaoId(),
            EVENT_TYPE,
            serializar(eventoSaida)));
  }

  private String serializar(AssinaturaRenovada evento) {
    try {
      return jsonMapper.writeValueAsString(evento);
    } catch (JacksonException e) {
      throw new IllegalStateException("Falha ao serializar evento AssinaturaRenovada", e);
    }
  }
}
