package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import com.globo.assinatura.pagamento.PagamentoEventoProcessado;
import com.globo.assinatura.pagamento.PagamentoEventoProcessadoRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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
public class ProcessarPagamento {

  private final AssinaturaRepository assinaturaRepository;
  private final PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;
  private final Clock clock;

  /**
   * Constroi o command com os repositorios e o relogio injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param pagamentoEventoProcessadoRepository repositorio de eventos de pagamento processados
   * @param clock relogio para calculo das datas de vigencia
   */
  public ProcessarPagamento(
      AssinaturaRepository assinaturaRepository,
      PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository,
      Clock clock) {
    this.assinaturaRepository = assinaturaRepository;
    this.pagamentoEventoProcessadoRepository = pagamentoEventoProcessadoRepository;
    this.clock = clock;
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
      return;
    }
    LocalDate hoje = LocalDate.now(clock);
    if (pagamentoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      return;
    }
    Optional<Assinatura> possivelAssinatura =
        assinaturaRepository.buscarPorUuidParaAtualizacao(evento.assinaturaId().toString());
    if (possivelAssinatura.isEmpty()) {
      return;
    }
    Assinatura assinatura = possivelAssinatura.get();
    if (evento.status() == StatusPagamento.REJECTED) {
      assinatura.recusarPagamento();
    } else {
      assinatura.ativar(hoje, hoje.plusMonths(1));
    }
    try {
      pagamentoEventoProcessadoRepository.save(
          new PagamentoEventoProcessado(
              evento.eventId(), assinatura.getUuid(), Instant.now(clock)));
    } catch (DataIntegrityViolationException e) {
      // outra transacao concorrente (ex.: consumer zumbi de um rebalance) ja registrou este
      // eventId entre o check de idempotencia e este save; o dominio ja foi atualizado de forma
      // idempotente acima, entao tratamos como no-op.
    }
  }
}
