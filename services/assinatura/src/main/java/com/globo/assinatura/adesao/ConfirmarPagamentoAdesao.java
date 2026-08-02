package com.globo.assinatura.adesao;

import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessado;
import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessadoRepository;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.shared.contrato.PagamentoStatusAtualizado;
import com.globo.assinatura.shared.contrato.StatusPagamento;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de processamento de evento de pagamento.
 *
 * <p>Atualiza o status da assinatura correlacionada ao evento recebido do Pagamento Service,
 * aplicando a transicao de dominio correspondente sob lock pessimista para garantir exclusao mutua
 * entre entregas concorrentes.
 */
@Service
public class ConfirmarPagamentoAdesao {

  private static final Logger log = LoggerFactory.getLogger(ConfirmarPagamentoAdesao.class);

  private final AssinaturaRepository assinaturaRepository;
  private final PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;
  private final Clock clock;
  private final long cicloMs;

  /**
   * Constroi o command com os repositorios, o relogio e a duracao do ciclo injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param pagamentoEventoProcessadoRepository repositorio de eventos de pagamento processados
   * @param clock relogio para calculo das datas de vigencia
   * @param cicloMs duracao do ciclo de renovacao em milissegundos
   */
  public ConfirmarPagamentoAdesao(
      AssinaturaRepository assinaturaRepository,
      PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository,
      Clock clock,
      @Value("${app.renovacao.ciclo-ms}") long cicloMs) {
    this.assinaturaRepository = assinaturaRepository;
    this.pagamentoEventoProcessadoRepository = pagamentoEventoProcessadoRepository;
    this.clock = clock;
    this.cicloMs = cicloMs;
  }

  /**
   * Processa o evento de pagamento atualizando a assinatura correlacionada e registrando o evento
   * para idempotencia.
   *
   * <p>Eventos cuja assinatura ainda nao existe sao ignorados silenciosamente: o evento pode chegar
   * antes da solicitacao de assinatura correspondente ter sido persistida (ordering eventual entre
   * os topicos). Nesse caso nada e registrado nem em idempotencia, permitindo que a redelivery pelo
   * container processe o evento novamente quando a assinatura ja existir.
   *
   * @param evento evento de status de pagamento recebido
   */
  @Transactional
  public void executar(PagamentoStatusAtualizado evento) {
    if (evento.status() == StatusPagamento.PENDING) {
      log.atDebug()
          .addKeyValue("event", "evento_ignorado_status_pending")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Evento de pagamento ignorado por status PENDING");
      return;
    }
    LocalDate hoje = LocalDate.now(clock);
    if (pagamentoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      log.atDebug()
          .addKeyValue("event", "evento_deduplicado")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Evento de pagamento ja processado");
      return;
    }
    Optional<Assinatura> possivelAssinatura =
        assinaturaRepository.buscarPorUuidParaAtualizacao(evento.assinaturaId().toString());
    if (possivelAssinatura.isEmpty()) {
      log.atDebug()
          .addKeyValue("event", "assinatura_desconhecida")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Assinatura do evento de pagamento inexistente");
      return;
    }
    Assinatura assinatura = possivelAssinatura.get();
    if (evento.status() == StatusPagamento.REJECTED) {
      assinatura.recusarPagamento();
    } else {
      assinatura.ativar(hoje, hoje.plusDays(Duration.ofMillis(cicloMs).toDays()));
    }
    try {
      pagamentoEventoProcessadoRepository.save(
          new PagamentoEventoProcessado(
              evento.eventId(), assinatura.getUuid(), Instant.now(clock)));
    } catch (DataIntegrityViolationException e) {
      log.atDebug()
          .addKeyValue("event", "pagamento_evento_deduplicado")
          .addKeyValue("eventId", evento.eventId())
          .addKeyValue("assinaturaId", assinatura.getUuid())
          .addKeyValue("reasonCode", "violacao_constraint_concorrente")
          .log("Evento de pagamento ja registrado por transacao concorrente");
    }
  }
}
