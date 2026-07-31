package com.globo.pagamento.webhook;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.messaging.event.PagamentoStatusAtualizado;
import com.globo.pagamento.messaging.event.StatusPagamento;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Command que processa uma notificacao de webhook do gateway.
 *
 * <p>Orquestra a autenticacao HMAC, a idempotencia por {@code eventId}, a consulta do status
 * oficial no gateway, a normalizacao, a atualizacao da cobranca e a publicacao publish-then-ACK. O
 * {@code eventId} so e gravado como processado apos o Kafka confirmar a publicacao, nunca antes.
 */
public class ProcessarWebhookPagamento {

  private final ObjectMapper objectMapper;
  private final HmacSignatureValidator hmacValidator;
  private final WebhookEventoProcessadoRepository eventoRepository;
  private final CobrancaRepository cobrancaRepository;
  private final GatewayPagamentoClient gatewayClient;
  private final NormalizadorStatus normalizador;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String topico;

  /**
   * Cria o command com suas dependencias.
   *
   * @param objectMapper mapeador JSON para serializar o evento publicado
   * @param hmacValidator validador da assinatura HMAC do corpo
   * @param eventoRepository repositorio de eventos processados (dedup por eventId)
   * @param cobrancaRepository repositorio de cobrancas
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status do gateway para o dominio publicado
   * @param kafkaTemplate template de publicacao no Kafka
   * @param topico topico de {@code pagamento-status-atualizado}
   */
  public ProcessarWebhookPagamento(
      ObjectMapper objectMapper,
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      KafkaTemplate<String, String> kafkaTemplate,
      String topico) {
    this.objectMapper = objectMapper;
    this.hmacValidator = hmacValidator;
    this.eventoRepository = eventoRepository;
    this.cobrancaRepository = cobrancaRepository;
    this.gatewayClient = gatewayClient;
    this.normalizador = normalizador;
    this.kafkaTemplate = kafkaTemplate;
    this.topico = topico;
  }

  /**
   * Processa a notificacao e retorna o eventId publicado.
   *
   * @param corpo corpo bruto da requisicao
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id})
   * @param assinaturaId uuid da assinatura (= {@code externalReference})
   * @param paymentId identificador da cobranca no gateway
   * @param assinatura valor do header {@code X-Mock-Signature}
   * @return o eventId processado
   * @throws WebhookInvalidoException se a assinatura HMAC for invalida ou ausente
   * @throws PublicacaoIndisponivelException se a consulta ao gateway ou a publicacao falharem
   */
  public UUID processar(
      byte[] corpo, UUID eventId, UUID assinaturaId, UUID paymentId, String assinatura) {
    if (!hmacValidator.valido(corpo, assinatura)) {
      throw new WebhookInvalidoException();
    }
    if (eventoRepository.existsByEventId(eventId)) {
      return eventId;
    }
    StatusGateway statusGateway = consultarStatus(paymentId);
    StatusPagamento status = normalizador.normalizar(statusGateway);
    atualizarCobranca(paymentId, status);
    publicar(eventId, assinaturaId, paymentId, status);
    eventoRepository.save(new WebhookEventoProcessado(eventId, assinaturaId));
    return eventId;
  }

  private StatusGateway consultarStatus(UUID paymentId) {
    try {
      return gatewayClient.consultarStatus(paymentId.toString());
    } catch (RuntimeException e) {
      throw new PublicacaoIndisponivelException();
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
      throw new PublicacaoIndisponivelException();
    } catch (ExecutionException | RuntimeException e) {
      throw new PublicacaoIndisponivelException();
    }
  }
}
