package com.globo.pagamento.webhook;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.outbox.OutboxRepository;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Configuracao dos beans do webhook de pagamento.
 *
 * <p>Monta os dois ramos atendidos pelo endpoint: {@link ProcessarWebhookPagamento} com o topico
 * {@code pagamento-status-atualizado} da adesao, e {@link ProcessarWebhookRenovacao} com o topico
 * {@code renovacao-resultado} da renovacao.
 */
@Configuration
public class WebhookConfig {

  private static final String TOPICO_PAGAMENTO_STATUS = "pagamento-status-atualizado";

  /**
   * Cria o command de processamento de webhook.
   *
   * @param objectMapper mapeador JSON para serializar o evento publicado
   * @param hmacValidator validador da assinatura HMAC
   * @param eventoRepository repositorio de eventos processados (dedup)
   * @param cobrancaRepository repositorio de cobrancas
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao, usado no despacho
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status
   * @param processarRenovacao command que decide o resultado de uma cobranca de renovacao
   * @param kafkaTemplate template de publicacao no Kafka
   * @return o command configurado
   */
  @Bean
  public ProcessarWebhookPagamento processarWebhookPagamento(
      ObjectMapper objectMapper,
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      ProcessarWebhookRenovacao processarRenovacao,
      KafkaTemplate<String, String> kafkaTemplate) {
    return new ProcessarWebhookPagamento(
        objectMapper,
        hmacValidator,
        eventoRepository,
        cobrancaRepository,
        pagamentoRenovacaoRepository,
        gatewayClient,
        normalizador,
        processarRenovacao,
        kafkaTemplate,
        TOPICO_PAGAMENTO_STATUS);
  }

  /**
   * Cria o command de decisao do resultado de uma cobranca de renovacao.
   *
   * @param tentativaRepository repositorio das tentativas de cobranca
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   * @param outboxRepository repositorio da outbox
   * @param backoffDias janela de espera por tentativa, via {@code
   *     app.renovacao.tentativas-backoff-dias}
   * @return o command configurado
   */
  @Bean
  public ProcessarWebhookRenovacao processarWebhookRenovacao(
      TentativaCobrancaRepository tentativaRepository,
      ObjectMapper objectMapper,
      OutboxRepository outboxRepository,
      @Value("${app.renovacao.tentativas-backoff-dias}") List<Integer> backoffDias) {
    return new ProcessarWebhookRenovacao(
        tentativaRepository, objectMapper, outboxRepository, backoffDias);
  }
}
