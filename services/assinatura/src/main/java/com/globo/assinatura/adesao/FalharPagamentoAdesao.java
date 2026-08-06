package com.globo.assinatura.adesao;

import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessado;
import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessadoRepository;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.shared.cache.CacheVersionado;
import com.globo.assinatura.shared.contrato.AssinaturaAdesaoEsgotada;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de processamento do esgotamento das tentativas de cobranca de adesao.
 *
 * <p>Transita a assinatura dona para {@link
 * com.globo.assinatura.assinatura.StatusAssinatura#PAGAMENTO_FALHOU} sob lock pessimista para
 * garantir exclusao mutua entre entregas concorrentes.
 */
@Service
public class FalharPagamentoAdesao {

  private static final Logger log = LoggerFactory.getLogger(FalharPagamentoAdesao.class);

  private final AssinaturaRepository assinaturaRepository;
  private final PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;
  private final UsuarioRepository usuarioRepository;
  private final CacheVersionado cacheVersionado;
  private final Clock clock;

  /**
   * Constroi o command com os repositorios, o cache e o relogio injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param pagamentoEventoProcessadoRepository repositorio de eventos de pagamento processados
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param cacheVersionado primitivas do cache distribuido para invalidar a listagem
   * @param clock relogio para o instante de processamento
   */
  public FalharPagamentoAdesao(
      AssinaturaRepository assinaturaRepository,
      PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository,
      UsuarioRepository usuarioRepository,
      CacheVersionado cacheVersionado,
      Clock clock) {
    this.assinaturaRepository = assinaturaRepository;
    this.pagamentoEventoProcessadoRepository = pagamentoEventoProcessadoRepository;
    this.usuarioRepository = usuarioRepository;
    this.cacheVersionado = cacheVersionado;
    this.clock = clock;
  }

  /**
   * Processa o evento de esgotamento atualizando a assinatura correlacionada.
   *
   * <p>Eventos ja processados sao ignorados sem acquire lock. Assinatura inexistente e ignorada
   * silenciosamente para permitir redelivery ate a assinatura surgir.
   *
   * @param evento evento de adesao esgotada recebido
   */
  @Transactional
  public void executar(AssinaturaAdesaoEsgotada evento) {
    if (pagamentoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      log.atDebug()
          .addKeyValue("event", "adesao_esgotada_deduplicada")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Evento de adesao esgotada ja processado");
      return;
    }
    Optional<Assinatura> possivelAssinatura =
        assinaturaRepository.buscarPorUuidParaAtualizacao(evento.assinaturaId().toString());
    if (possivelAssinatura.isEmpty()) {
      log.atDebug()
          .addKeyValue("event", "adesao_esgotada_assinatura_inexistente")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Assinatura do evento de adesao esgotada inexistente");
      return;
    }
    Assinatura assinatura = possivelAssinatura.get();
    assinatura.falharPagamento();
    try {
      pagamentoEventoProcessadoRepository.save(
          new PagamentoEventoProcessado(
              evento.eventId(), assinatura.getUuid(), Instant.now(clock)));
    } catch (DataIntegrityViolationException e) {
      log.atDebug()
          .addKeyValue("event", "adesao_esgotada_deduplicada_concorrente")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", assinatura.getUuid())
          .addKeyValue("reasonCode", "violacao_constraint_concorrente")
          .log("Evento de adesao esgotada ja registrado por transacao concorrente");
    }
    invalidarCache(assinatura);
    log.atInfo()
        .addKeyValue("event", "adesao_pagamento_falhou")
        .addKeyValue("assinaturaId", assinatura.getUuid())
        .log("Assinatura marcada como pagamento falhou");
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
                  .log("Cache de listagem invalidado apos falha de pagamento");
            });
  }
}
