package com.globo.pagamento.renovacao;

import com.globo.pagamento.messaging.event.RenovacaoSolicitada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de criacao do pagamento de uma renovacao a partir de {@link RenovacaoSolicitada}.
 *
 * <p>Idempotente por {@code renovacaoId}: a insercao do pagamento usa {@code ON CONFLICT DO
 * NOTHING}, e a primeira tentativa de cobranca so e criada quando a linha foi de fato inserida.
 * Assim, um redelivery nao duplica pagamento nem tentativa.
 *
 * <p>A tentativa inicial nasce {@link StatusTentativa#PENDENTE}, sem {@code paymentId}: o scheduler
 * que cobra no gateway (BE-12) preenche esses campos quando a cobra.
 */
@Service
public class CriarPagamentoRenovacaoService {

  static final int PRIMEIRA_TENTATIVA = 1;

  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final TentativaCobrancaRepository tentativaCobrancaRepository;

  /**
   * Cria o service.
   *
   * @param pagamentoRenovacaoRepository repositorio do pagamento de renovacao
   * @param tentativaCobrancaRepository repositorio das tentativas de cobranca
   */
  public CriarPagamentoRenovacaoService(
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      TentativaCobrancaRepository tentativaCobrancaRepository) {
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.tentativaCobrancaRepository = tentativaCobrancaRepository;
  }

  /**
   * Processa o evento de renovacao solicitada, criando o pagamento e a primeira tentativa se a
   * renovacao ainda nao existir.
   *
   * @param evento evento consumido do topico {@code renovacao-solicitada}
   */
  @Transactional
  public void processar(RenovacaoSolicitada evento) {
    int inseridas =
        pagamentoRenovacaoRepository.inserirSeNaoExistir(
            evento.renovacaoId().toString(),
            evento.assinaturaId().toString(),
            evento.plano(),
            evento.valor(),
            evento.cicloReferencia());
    if (inseridas == 0) {
      return;
    }
    tentativaCobrancaRepository.save(
        new TentativaCobranca(evento.renovacaoId().toString(), PRIMEIRA_TENTATIVA));
  }
}
