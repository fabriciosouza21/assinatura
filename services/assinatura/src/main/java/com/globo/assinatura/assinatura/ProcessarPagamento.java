package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
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

  /**
   * Constroi o command com o repositorio de assinaturas injetado.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   */
  public ProcessarPagamento(AssinaturaRepository assinaturaRepository) {
    this.assinaturaRepository = assinaturaRepository;
  }

  /**
   * Processa o evento de pagamento atualizando a assinatura correlacionada.
   *
   * @param evento evento de status de pagamento recebido
   */
  @Transactional
  public void executar(PagamentoStatusAtualizado evento) {
    LocalDate hoje = LocalDate.now();
    assinaturaRepository
        .findByUuidForUpdate(evento.assinaturaId().toString())
        .orElseThrow()
        .ativar(hoje, hoje);
  }
}
