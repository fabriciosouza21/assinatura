package com.globo.pagamento.cancelamento;

import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cancela as tentativas pendentes das renovacoes de uma assinatura. */
@Service
public class CancelarTentativasPendentes {

  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final TentativaCobrancaRepository tentativaCobrancaRepository;

  /**
   * Constroi o service com os repositorios de renovacoes e tentativas injetados.
   *
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao
   * @param tentativaCobrancaRepository repositorio de tentativas de cobranca
   */
  public CancelarTentativasPendentes(
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      TentativaCobrancaRepository tentativaCobrancaRepository) {
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.tentativaCobrancaRepository = tentativaCobrancaRepository;
  }

  /**
   * Cancela as tentativas pendentes das renovacoes de uma assinatura.
   *
   * @param assinaturaId identificador publico da assinatura
   */
  @Transactional
  public void executar(String assinaturaId) {
    for (PagamentoRenovacao renovacao :
        pagamentoRenovacaoRepository.findByAssinaturaId(assinaturaId)) {
      for (TentativaCobranca tentativa :
          tentativaCobrancaRepository.buscarPendentesPorRenovacaoId(renovacao.getRenovacaoId())) {
        tentativa.cancelar();
        tentativaCobrancaRepository.save(tentativa);
      }
    }
  }
}
