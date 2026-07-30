package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.pagamento.PagamentoEventoProcessado;
import com.globo.assinatura.pagamento.PagamentoEventoProcessadoRepository;
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

  /**
   * Constroi o command com os repositorios injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param pagamentoEventoProcessadoRepository repositorio de eventos de pagamento processados
   */
  public ProcessarPagamento(
      AssinaturaRepository assinaturaRepository,
      PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository) {
    this.assinaturaRepository = assinaturaRepository;
    this.pagamentoEventoProcessadoRepository = pagamentoEventoProcessadoRepository;
  }

  /**
   * Processa o evento de pagamento atualizando a assinatura correlacionada e registrando o evento
   * para idempotencia.
   *
   * @param evento evento de status de pagamento recebido
   */
  @Transactional
  public void executar(PagamentoStatusAtualizado evento) {
    LocalDate hoje = LocalDate.now();
    Assinatura assinatura =
        assinaturaRepository.findByUuidForUpdate(evento.assinaturaId().toString()).orElseThrow();
    assinatura.ativar(hoje, hoje);
    pagamentoEventoProcessadoRepository.save(
        new PagamentoEventoProcessado(evento.eventId(), assinatura.getUuid()));
  }
}
