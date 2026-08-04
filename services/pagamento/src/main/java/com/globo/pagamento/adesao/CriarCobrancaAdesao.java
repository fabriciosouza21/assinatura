package com.globo.pagamento.adesao;

import com.globo.pagamento.shared.contrato.AssinaturaSolicitada;
import org.springframework.stereotype.Service;

/**
 * Command de criacao da tentativa de cobranca de adesao a partir de um evento {@link
 * AssinaturaSolicitada}.
 *
 * <p>Idempotente por {@code assinaturaId}: se ja existir tentativa, conclui sem nova acao. Caso
 * contrario, persiste a tentativa em {@link StatusTentativaAdesao#PENDENTE} antes de qualquer
 * chamada ao gateway, garantindo que uma falha tecnica do gateway nao deixe a assinatura sem
 * registro de cobranca. O scheduler assume a cobranca no proximo ciclo.
 */
@Service
public class CriarCobrancaAdesao {

  private final CobrancaAdesaoTentativaRepository tentativaRepository;

  /**
   * Cria o service.
   *
   * @param tentativaRepository repositorio de tentativas de adesao
   */
  public CriarCobrancaAdesao(CobrancaAdesaoTentativaRepository tentativaRepository) {
    this.tentativaRepository = tentativaRepository;
  }

  /**
   * Processa o evento de assinatura solicitada, criando a tentativa de cobranca se necessario.
   *
   * @param evento evento consumido do topico {@code assinatura-solicitada}
   */
  public void processar(AssinaturaSolicitada evento) {
    String assinaturaId = evento.assinaturaId().toString();
    if (tentativaRepository.findByAssinaturaUuid(assinaturaId).isPresent()) {
      return;
    }
    tentativaRepository.save(new CobrancaAdesaoTentativa(assinaturaId, evento.valor()));
  }
}
