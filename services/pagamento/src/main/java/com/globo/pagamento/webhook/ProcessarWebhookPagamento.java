package com.globo.pagamento.webhook;

import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.shared.contrato.StatusPagamento;
import com.globo.pagamento.webhook.api.HmacSignatureValidator;
import com.globo.pagamento.webhook.idempotencia.WebhookEventoProcessadoRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command que processa uma notificacao de webhook do gateway.
 *
 * <p>Orquestra a autenticacao HMAC, a idempotencia por {@code eventId}, a consulta do status
 * oficial no gateway e a normalizacao, delegando o registro do resultado (decisao + outbox +
 * deduplicacao) a {@link RegistrarResultadoWebhook}, que roda numa transacao propria. O ack do
 * Kafka sai do caminho do cliente e fica com o publisher da outbox.
 *
 * <p>Um unico endpoint atende dois fluxos, distinguidos pelo {@code externalReference} da
 * notificacao: quando ele resolve um {@link PagamentoRenovacao} conhecido, a decisao e delegada ao
 * fluxo de renovacao; caso contrario, o {@code externalReference} e o {@code assinaturaId} da
 * adesao e o fluxo publica {@link com.globo.pagamento.shared.contrato.PagamentoStatusAtualizado}.
 */
public class ProcessarWebhookPagamento {

  private static final Logger log = LoggerFactory.getLogger(ProcessarWebhookPagamento.class);

  private final HmacSignatureValidator hmacValidator;
  private final WebhookEventoProcessadoRepository eventoRepository;
  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final GatewayPagamentoClient gatewayClient;
  private final NormalizadorStatus normalizador;
  private final RegistrarResultadoWebhook registrarResultado;

  /**
   * Cria o command com suas dependencias.
   *
   * @param hmacValidator validador da assinatura HMAC do corpo
   * @param eventoRepository repositorio de eventos processados (dedup por eventId)
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao, usado no despacho
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status do gateway para o dominio publicado
   * @param registrarResultado command que registra o resultado do webhook na outbox
   */
  public ProcessarWebhookPagamento(
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      RegistrarResultadoWebhook registrarResultado) {
    this.hmacValidator = hmacValidator;
    this.eventoRepository = eventoRepository;
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.gatewayClient = gatewayClient;
    this.normalizador = normalizador;
    this.registrarResultado = registrarResultado;
  }

  /**
   * Processa a notificacao e retorna o eventId registrado.
   *
   * @param corpo corpo bruto da requisicao
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id})
   * @param externalReference referencia externa da cobranca: o {@code renovacaoId} numa renovacao,
   *     o {@code assinaturaId} numa adesao
   * @param paymentId identificador da cobranca no gateway
   * @param assinatura valor do header {@code X-Mock-Signature}
   * @return o eventId processado
   * @throws WebhookInvalidoException se a assinatura HMAC for invalida ou ausente
   * @throws PublicacaoIndisponivelException se a consulta ao gateway falhar
   * @throws DecisaoRenovacaoIndisponivelException se a decisao de renovacao nao for possivel
   */
  public UUID processar(
      byte[] corpo, UUID eventId, UUID externalReference, UUID paymentId, String assinatura) {
    if (!hmacValidator.valido(corpo, assinatura)) {
      throw new WebhookInvalidoException();
    }
    if (eventoRepository.existsByEventId(eventId)) {
      log.atDebug()
          .addKeyValue("event", "webhook_reenvio_ignorado")
          .addKeyValue("eventId", eventId)
          .addKeyValue("reasonCode", "event_id_ja_processado")
          .log("Reenvio de webhook ja processado");
      return eventId;
    }
    log.atInfo()
        .addKeyValue("event", "webhook_recebido")
        .addKeyValue("eventId", eventId)
        .addKeyValue("externalReference", externalReference)
        .addKeyValue("paymentId", paymentId)
        .log("Webhook recebido");
    StatusGateway statusGateway = consultarStatus(paymentId);
    StatusPagamento status = normalizador.normalizar(statusGateway);
    Optional<PagamentoRenovacao> renovacao =
        pagamentoRenovacaoRepository.findByRenovacaoId(externalReference.toString());
    registrarResultado.registrar(eventId, externalReference, paymentId, status, renovacao);
    return eventId;
  }

  private StatusGateway consultarStatus(UUID paymentId) {
    try {
      return gatewayClient.consultarStatus(paymentId.toString());
    } catch (RuntimeException e) {
      throw new PublicacaoIndisponivelException(e);
    }
  }
}
