package com.globo.pagamento.webhook;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Configuracao dos beans do webhook de pagamento.
 *
 * <p>Monta o command {@link ProcessarWebhookPagamento} com o topico {@code
 * pagamento-status-atualizado} publicado pelo Pagamento Service.
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
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status
   * @param kafkaTemplate template de publicacao no Kafka
   * @return o command configurado
   */
  @Bean
  public ProcessarWebhookPagamento processarWebhookPagamento(
      ObjectMapper objectMapper,
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      KafkaTemplate<String, String> kafkaTemplate) {
    return new ProcessarWebhookPagamento(
        objectMapper,
        hmacValidator,
        eventoRepository,
        cobrancaRepository,
        gatewayClient,
        normalizador,
        kafkaTemplate,
        TOPICO_PAGAMENTO_STATUS);
  }
}
