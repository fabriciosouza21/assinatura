package com.globo.pagamento.cobranca;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.messaging.event.AssinaturaSolicitada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command de criacao de cobranca a partir de um evento {@link AssinaturaSolicitada}.
 *
 * <p>Idempotente por {@code assinaturaId}: se ja existir correlacao, conclui sem nova chamada ao
 * gateway. Caso contrario, cria a cobranca no gateway e persiste a correlacao em {@code PENDING}.
 */
@Service
public class CriarCobrancaService {

  private final CobrancaRepository cobrancaRepository;
  private final GatewayPagamentoClient gatewayPagamentoClient;

  /**
   * Cria o service.
   *
   * @param cobrancaRepository repositorio de correlacao
   * @param gatewayPagamentoClient client do gateway de pagamento
   */
  public CriarCobrancaService(
      CobrancaRepository cobrancaRepository, GatewayPagamentoClient gatewayPagamentoClient) {
    this.cobrancaRepository = cobrancaRepository;
    this.gatewayPagamentoClient = gatewayPagamentoClient;
  }

  /**
   * Processa o evento de assinatura solicitada, criando a cobranca se necessario.
   *
   * @param evento evento consumido do topico {@code assinatura-solicitada}
   */
  @Transactional
  public void processar(AssinaturaSolicitada evento) {
    String assinaturaId = evento.assinaturaId().toString();
    if (cobrancaRepository.existsByAssinaturaUuid(assinaturaId)) {
      return;
    }
    CobrancaCriada cobranca = gatewayPagamentoClient.criarCobranca(assinaturaId, evento.valor());
    cobrancaRepository.save(
        new Cobranca(assinaturaId, cobranca.paymentId(), StatusCobranca.PENDING));
  }
}
