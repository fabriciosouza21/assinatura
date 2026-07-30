package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import com.globo.assinatura.pagamento.PagamentoEventoProcessado;
import com.globo.assinatura.pagamento.PagamentoEventoProcessadoRepository;
import java.time.Clock;
import java.time.LocalDate;
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
   * @param evento evento de status de pagamento recebido
   */
  @Transactional
  public void executar(PagamentoStatusAtualizado evento) {
    if (evento.status() == StatusPagamento.PENDING) {
      return;
    }
    LocalDate hoje = LocalDate.now(clock);
    Assinatura assinatura =
        assinaturaRepository.findByUuidForUpdate(evento.assinaturaId().toString()).orElseThrow();
    if (evento.status() == StatusPagamento.REJECTED) {
      assinatura.recusarPagamento();
    } else {
      assinatura.ativar(hoje, hoje.plusMonths(1));
    }
    pagamentoEventoProcessadoRepository.save(
        new PagamentoEventoProcessado(evento.eventId(), assinatura.getUuid()));
  }
}
