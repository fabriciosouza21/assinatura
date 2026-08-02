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
 * que cobra a tentativa no gateway preenche esse campo. Ela e criada pelo agregado {@link
 * PagamentoRenovacao}, recarregado apos a insercao, para que a sequencia de tentativas nasca sempre
 * do mesmo lugar.
 */
@Service
public class CriarPagamentoRenovacaoService {

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
   * @throws IllegalStateException se o pagamento recem-inserido nao puder ser recarregado
   */
  @Transactional
  public void processar(RenovacaoSolicitada evento) {
    String renovacaoId = evento.renovacaoId().toString();
    int inseridas =
        pagamentoRenovacaoRepository.inserirSeNaoExistir(
            renovacaoId,
            evento.assinaturaId().toString(),
            evento.plano().name(),
            evento.valor(),
            evento.cicloReferencia());
    if (inseridas == 0) {
      return;
    }
    PagamentoRenovacao pagamento =
        pagamentoRenovacaoRepository
            .findByRenovacaoId(renovacaoId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "pagamento da renovacao "
                            + renovacaoId
                            + " nao encontrado apos a insercao"));
    tentativaCobrancaRepository.save(pagamento.registrarTentativa());
  }
}
