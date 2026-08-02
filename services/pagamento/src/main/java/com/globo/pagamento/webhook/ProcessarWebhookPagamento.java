package com.globo.pagamento.webhook;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.messaging.event.PagamentoStatusAtualizado;
import com.globo.pagamento.messaging.event.StatusPagamento;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Command que processa uma notificacao de webhook do gateway.
 *
 * <p>Orquestra a autenticacao HMAC, a idempotencia por {@code eventId}, a consulta do status
 * oficial no gateway, a normalizacao e a publicacao publish-then-ACK. O {@code eventId} so e
 * gravado como processado apos o Kafka confirmar a publicacao, nunca antes.
 *
 * <p>Um unico endpoint atende dois fluxos, distinguidos pelo {@code externalReference} da
 * notificacao: quando ele resolve um {@link PagamentoRenovacao} conhecido, a decisao e delegada a
 * {@link ProcessarWebhookRenovacao}; caso contrario, o {@code externalReference} e o {@code
 * assinaturaId} da adesao e o fluxo publica {@link PagamentoStatusAtualizado}.
 */
public class ProcessarWebhookPagamento {

  private static final Logger log = LoggerFactory.getLogger(ProcessarWebhookPagamento.class);

  private final ObjectMapper objectMapper;
  private final HmacSignatureValidator hmacValidator;
  private final WebhookEventoProcessadoRepository eventoRepository;
  private final CobrancaRepository cobrancaRepository;
  private final PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  private final GatewayPagamentoClient gatewayClient;
  private final NormalizadorStatus normalizador;
  private final ProcessarWebhookRenovacao processarRenovacao;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String topico;

  /**
   * Cria o command com suas dependencias.
   *
   * @param objectMapper mapeador JSON para serializar o evento publicado
   * @param hmacValidator validador da assinatura HMAC do corpo
   * @param eventoRepository repositorio de eventos processados (dedup por eventId)
   * @param cobrancaRepository repositorio de cobrancas
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao, usado no despacho
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status do gateway para o dominio publicado
   * @param processarRenovacao command que decide o resultado de uma cobranca de renovacao
   * @param kafkaTemplate template de publicacao no Kafka
   * @param topico topico de {@code pagamento-status-atualizado}
   */
  public ProcessarWebhookPagamento(
      ObjectMapper objectMapper,
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      ProcessarWebhookRenovacao processarRenovacao,
      KafkaTemplate<String, String> kafkaTemplate,
      String topico) {
    this.objectMapper = objectMapper;
    this.hmacValidator = hmacValidator;
    this.eventoRepository = eventoRepository;
    this.cobrancaRepository = cobrancaRepository;
    this.pagamentoRenovacaoRepository = pagamentoRenovacaoRepository;
    this.gatewayClient = gatewayClient;
    this.normalizador = normalizador;
    this.processarRenovacao = processarRenovacao;
    this.kafkaTemplate = kafkaTemplate;
    this.topico = topico;
  }

  /**
   * Processa a notificacao e retorna o eventId publicado.
   *
   * @param corpo corpo bruto da requisicao
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id})
   * @param externalReference referencia externa da cobranca: o {@code renovacaoId} numa renovacao,
   *     o {@code assinaturaId} numa adesao
   * @param paymentId identificador da cobranca no gateway
   * @param assinatura valor do header {@code X-Mock-Signature}
   * @return o eventId processado
   * @throws WebhookInvalidoException se a assinatura HMAC for invalida ou ausente
   * @throws PublicacaoIndisponivelException se a consulta ao gateway ou a publicacao falharem
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
    UUID assinaturaId = externalReference;
    if (renovacao.isPresent()) {
      PagamentoRenovacao pagamento = renovacao.get();
      assinaturaId = UUID.fromString(pagamento.getAssinaturaId());
      processarRenovacao.decidir(pagamento, eventId, paymentId.toString(), status);
    } else {
      atualizarCobranca(paymentId, status);
      publicar(eventId, externalReference, paymentId, status);
    }
    try {
      eventoRepository.save(new WebhookEventoProcessado(eventId, assinaturaId));
    } catch (DataIntegrityViolationException e) {
      log.atDebug()
          .addKeyValue("event", "webhook_evento_deduplicado")
          .addKeyValue("eventId", eventId)
          .addKeyValue("assinaturaId", assinaturaId)
          .addKeyValue("reasonCode", "violacao_constraint_concorrente")
          .log("Evento de webhook ja registrado por transacao concorrente");
      return eventId;
    }
    return eventId;
  }

  private StatusGateway consultarStatus(UUID paymentId) {
    try {
      return gatewayClient.consultarStatus(paymentId.toString());
    } catch (RuntimeException e) {
      throw new PublicacaoIndisponivelException(e);
    }
  }

  private void atualizarCobranca(UUID paymentId, StatusPagamento status) {
    cobrancaRepository
        .findByPaymentId(paymentId.toString())
        .ifPresent(
            cobranca -> {
              cobranca.marcarComo(StatusCobranca.valueOf(status.name()));
              cobrancaRepository.save(cobranca);
            });
  }

  private void publicar(UUID eventId, UUID assinaturaId, UUID paymentId, StatusPagamento status) {
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(eventId, Instant.now(), assinaturaId, status, paymentId);
    String payload = objectMapper.writeValueAsString(evento);
    try {
      kafkaTemplate.send(topico, assinaturaId.toString(), payload).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublicacaoIndisponivelException(e);
    } catch (ExecutionException | RuntimeException e) {
      throw new PublicacaoIndisponivelException(e);
    }
  }
}
