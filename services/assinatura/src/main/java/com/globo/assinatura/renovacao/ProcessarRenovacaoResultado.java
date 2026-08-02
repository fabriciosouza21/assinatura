package com.globo.assinatura.renovacao;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.renovacao.idempotencia.RenovacaoEventoProcessado;
import com.globo.assinatura.renovacao.idempotencia.RenovacaoEventoProcessadoRepository;
import com.globo.assinatura.shared.cache.CacheVersionado;
import com.globo.assinatura.shared.contrato.AssinaturaRenovada;
import com.globo.assinatura.shared.contrato.AssinaturaSuspensa;
import com.globo.assinatura.shared.contrato.PagamentoRenovacaoAprovado;
import com.globo.assinatura.shared.contrato.RenovacaoTentativasEsgotadas;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

  private static final Logger log = LoggerFactory.getLogger(ProcessarRenovacaoResultado.class);

  private static final String AGGREGATE_TYPE = "Renovacao";
  private static final String EVENT_TYPE = "AssinaturaRenovada";
  private static final String EVENT_TYPE_ESGOTADO = "AssinaturaSuspensa";

  private final RenovacaoRepository renovacaoRepository;
  private final AssinaturaRepository assinaturaRepository;
  private final RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository;
  private final UsuarioRepository usuarioRepository;
  private final CacheVersionado cacheVersionado;
  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final long cicloMs;

  /**
   * Constroi o command com os repositorios, a outbox, o serializador JSON, o relogio e a duracao do
   * ciclo injetados.
   *
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param renovacaoEventoProcessadoRepository repositorio de idempotencia de eventos de renovacao
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param cacheVersionado primitivas do cache distribuido para invalidar a listagem
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON do evento de dominio
   * @param clock relogio para calculo do instante do evento
   * @param cicloMs duracao do ciclo de renovacao em milissegundos
   */
  public ProcessarRenovacaoResultado(
      RenovacaoRepository renovacaoRepository,
      AssinaturaRepository assinaturaRepository,
      RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository,
      UsuarioRepository usuarioRepository,
      CacheVersionado cacheVersionado,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper,
      Clock clock,
      @Value("${app.renovacao.ciclo-ms}") long cicloMs) {
    this.renovacaoRepository = renovacaoRepository;
    this.assinaturaRepository = assinaturaRepository;
    this.renovacaoEventoProcessadoRepository = renovacaoEventoProcessadoRepository;
    this.usuarioRepository = usuarioRepository;
    this.cacheVersionado = cacheVersionado;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
    this.cicloMs = cicloMs;
  }

  /**
   * Processa o evento de renovacao aprovada atualizando a assinatura dona.
   *
   * <p>Eventos ja processados sao ignorados pelo identificador unico, sem acquire lock sobre a
   * renovacao. Eventos cuja renovacao ainda nao existe tambem sao ignorados, sem registrar
   * idempotencia, permitindo que o redelivery processe o evento quando a renovacao surgir. Entregas
   * tardias sobre uma renovacao ja resolvida (status diferente de PENDENTE) sao tratadas como no-op
   * idempotente. Se a assinatura dona da renovacao nao existir, o evento e descartado com alerta,
   * sem registrar idempotencia, pois se trata de quebra de integridade referencial irrecuperavel.
   * Caso contrario, carrega a renovacao sob lock pessimista pelo uuid publico, resolve a assinatura
   * dona pelo identificador interno, confirma a renovacao do ciclo e grava o evento {@code
   * AssinaturaRenovada} na outbox na mesma transacao.
   *
   * @param evento evento de pagamento de renovacao aprovado
   */
  @Transactional
  public void executar(PagamentoRenovacaoAprovado evento) {
    if (renovacaoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      log.atDebug()
          .addKeyValue("event", "renovacao_evento_deduplicado")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .log("Evento de renovacao aprovada ja processado");
      return;
    }
    Optional<Renovacao> possivelRenovacao =
        renovacaoRepository.buscarPorUuidParaAtualizacao(evento.renovacaoId().toString());
    if (possivelRenovacao.isEmpty()) {
      log.atDebug()
          .addKeyValue("event", "renovacao_desconhecida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .log("Renovacao do evento de renovacao aprovada inexistente");
      return;
    }
    Renovacao renovacao = possivelRenovacao.get();
    if (renovacao.getStatus() != StatusRenovacao.PENDENTE) {
      log.atDebug()
          .addKeyValue("event", "renovacao_ja_resolvida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("status", renovacao.getStatus())
          .log("Evento de renovacao aprovada descartado por renovacao ja resolvida");
      return;
    }
    Optional<Assinatura> possivelAssinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId());
    if (possivelAssinatura.isEmpty()) {
      log.atWarn()
          .addKeyValue("event", "assinatura_desconhecida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("assinaturaId", renovacao.getAssinaturaId())
          .log("Assinatura dona da renovacao aprovada inexistente");
      return;
    }
    Assinatura assinatura = possivelAssinatura.get();
    renovacao.aprovar();
    LocalDate novoFimCiclo = assinatura.getFimCiclo().plusDays(Duration.ofMillis(cicloMs).toDays());
    assinatura.renovar(novoFimCiclo, Instant.now(clock).plusMillis(cicloMs));
    gravarEvento(assinatura, renovacao, evento);
    registrarIdempotencia(evento.eventId(), renovacao.getUuid());
    invalidarCache(assinatura);
  }

  /**
   * Processa o evento de tentativas esgotadas suspendendo a assinatura dona.
   *
   * <p>Segue o mesmo contrato de idempotencia e lock de {@link
   * #executar(PagamentoRenovacaoAprovado)}: eventos ja processados sao ignorados sem acquire lock;
   * eventos para renovacao inexistente sao ignorados sem registrar idempotencia; entregas tardias
   * sobre renovacao ja resolvida sao tratadas como no-op idempotente; assinatura dona inexistente e
   * descartada com alerta. Caso contrario, esgota as tentativas da renovacao, suspende a assinatura
   * dona e grava o evento {@code AssinaturaSuspensa} na outbox na mesma transacao.
   *
   * @param evento evento de tentativas de renovacao esgotadas
   */
  @Transactional
  public void executar(RenovacaoTentativasEsgotadas evento) {
    if (renovacaoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      log.atDebug()
          .addKeyValue("event", "renovacao_evento_deduplicado")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .log("Evento de renovacao esgotada ja processado");
      return;
    }
    Optional<Renovacao> possivelRenovacao =
        renovacaoRepository.buscarPorUuidParaAtualizacao(evento.renovacaoId().toString());
    if (possivelRenovacao.isEmpty()) {
      log.atDebug()
          .addKeyValue("event", "renovacao_desconhecida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .log("Renovacao do evento de renovacao esgotada inexistente");
      return;
    }
    Renovacao renovacao = possivelRenovacao.get();
    if (renovacao.getStatus() != StatusRenovacao.PENDENTE) {
      log.atDebug()
          .addKeyValue("event", "renovacao_ja_resolvida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("status", renovacao.getStatus())
          .log("Evento de renovacao esgotada descartado por renovacao ja resolvida");
      return;
    }
    Optional<Assinatura> possivelAssinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId());
    if (possivelAssinatura.isEmpty()) {
      log.atWarn()
          .addKeyValue("event", "assinatura_desconhecida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("assinaturaId", renovacao.getAssinaturaId())
          .log("Assinatura dona da renovacao esgotada inexistente");
      return;
    }
    Assinatura assinatura = possivelAssinatura.get();
    renovacao.esgotarTentativas();
    assinatura.suspender();
    gravarEventoSuspensao(assinatura, renovacao);
    registrarIdempotencia(evento.eventId(), renovacao.getUuid());
    invalidarCache(assinatura);
  }

  private void invalidarCache(Assinatura assinatura) {
    usuarioRepository
        .findById(assinatura.getUsuarioId())
        .ifPresent(
            usuario -> {
              cacheVersionado.invalidarAposCommit("assinatura:list:versao:" + usuario.getUuid());
              log.atInfo()
                  .addKeyValue("event", "assinatura_lista_cache_invalidada")
                  .addKeyValue("usuarioId", usuario.getUuid())
                  .log("Cache de listagem invalidado apos o resultado de renovacao");
            });
  }

  private void registrarIdempotencia(UUID eventId, String renovacaoUuid) {
    try {
      renovacaoEventoProcessadoRepository.save(
          new RenovacaoEventoProcessado(eventId, renovacaoUuid, Instant.now(clock)));
    } catch (DataIntegrityViolationException e) {
      log.atDebug()
          .addKeyValue("event", "renovacao_evento_deduplicado")
          .addKeyValue("eventId", eventId)
          .addKeyValue("renovacaoId", renovacaoUuid)
          .addKeyValue("reasonCode", "violacao_constraint_concorrente")
          .log("Evento de renovacao ja registrado por transacao concorrente");
    }
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
