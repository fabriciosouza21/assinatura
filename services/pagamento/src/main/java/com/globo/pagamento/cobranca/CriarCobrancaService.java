package com.globo.pagamento.cobranca;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.messaging.event.AssinaturaSolicitada;
import org.springframework.stereotype.Service;

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
   * <p>A criacao da cobranca no gateway ocorre fora de qualquer transacao de banco, evitando
   * segurar uma conexao do pool durante a chamada de rede. A persistencia fica a cargo do {@code
   * save} do repositorio, com a unicidade por {@code assinaturaUuid} como rede de segurança da
   * idempotencia.
   *
   * @param evento evento consumido do topico {@code assinatura-solicitada}
   */
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
