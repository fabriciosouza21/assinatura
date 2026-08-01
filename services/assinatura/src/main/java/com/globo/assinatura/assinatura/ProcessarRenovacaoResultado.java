package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import com.globo.assinatura.renovacao.RenovacaoEventoProcessadoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de processamento do resultado de uma renovacao.
 *
 * <p>Processa o evento de pagamento aprovado pela renovacao aplicando a transicao de dominio
 * correspondente na assinatura dona sob lock pessimista para garantir exclusao mutua entre entregas
 * concorrentes.
 */
@Service
public class ProcessarRenovacaoResultado {

  private final RenovacaoRepository renovacaoRepository;
  private final AssinaturaRepository assinaturaRepository;
  private final RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository;

  /**
   * Constroi o command com os repositorios injetados.
   *
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param renovacaoEventoProcessadoRepository repositorio de idempotencia de eventos de renovacao
   */
  public ProcessarRenovacaoResultado(
      RenovacaoRepository renovacaoRepository,
      AssinaturaRepository assinaturaRepository,
      RenovacaoEventoProcessadoRepository renovacaoEventoProcessadoRepository) {
    this.renovacaoRepository = renovacaoRepository;
    this.assinaturaRepository = assinaturaRepository;
    this.renovacaoEventoProcessadoRepository = renovacaoEventoProcessadoRepository;
  }

  /**
   * Processa o evento de renovacao aprovada atualizando a assinatura dona.
   *
   * <p>Eventos ja processados sao ignorados pelo identificador unico, sem acquire lock sobre a
   * renovacao. Caso contrario, carrega a renovacao sob lock pessimista pelo uuid publico, resolve a
   * assinatura dona pelo identificador interno e confirma a renovacao do ciclo.
   *
   * @param evento evento de pagamento de renovacao aprovado
   */
  @Transactional
  public void executar(PagamentoRenovacaoAprovado evento) {
    if (renovacaoEventoProcessadoRepository.existsByEventId(evento.eventId())) {
      return;
    }
    Renovacao renovacao =
        renovacaoRepository
            .buscarPorUuidParaAtualizacao(evento.renovacaoId().toString())
            .orElseThrow();
    Assinatura assinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId()).orElseThrow();
    assinatura.renovar(assinatura.getFimCiclo().plusMonths(1));
  }
}
