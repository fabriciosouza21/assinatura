package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
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

  /**
   * Constroi o command com os repositorios injetados.
   *
   * @param renovacaoRepository repositorio de persistencia de renovacoes
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   */
  public ProcessarRenovacaoResultado(
      RenovacaoRepository renovacaoRepository, AssinaturaRepository assinaturaRepository) {
    this.renovacaoRepository = renovacaoRepository;
    this.assinaturaRepository = assinaturaRepository;
  }

  /**
   * Processa o evento de renovacao aprovada atualizando a assinatura dona.
   *
   * <p>Carrega a renovacao sob lock pessimista pelo uuid publico, resolve a assinatura dona pelo
   * identificador interno e confirma a renovacao do ciclo.
   *
   * @param evento evento de pagamento de renovacao aprovado
   */
  @Transactional
  public void executar(PagamentoRenovacaoAprovado evento) {
    Renovacao renovacao =
        renovacaoRepository
            .buscarPorUuidParaAtualizacao(evento.renovacaoId().toString())
            .orElseThrow();
    Assinatura assinatura =
        assinaturaRepository.findById(renovacao.getAssinaturaId()).orElseThrow();
    assinatura.renovar(assinatura.getFimCiclo());
  }
}
