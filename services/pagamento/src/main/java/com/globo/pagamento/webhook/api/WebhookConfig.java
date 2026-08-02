package com.globo.pagamento.webhook.api;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import com.globo.pagamento.webhook.NormalizadorStatus;
import com.globo.pagamento.webhook.ProcessarWebhookPagamento;
import com.globo.pagamento.webhook.ProcessarWebhookRenovacao;
import com.globo.pagamento.webhook.RegistrarResultadoWebhook;
import com.globo.pagamento.webhook.idempotencia.WebhookEventoProcessadoRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * Configuracao dos beans do webhook de pagamento.
 *
 * <p>Monta os dois ramos atendidos pelo endpoint: {@link ProcessarWebhookPagamento} com o {@link
 * RegistrarResultadoWebhook} que registra o resultado na outbox, e {@link
 * ProcessarWebhookRenovacao} com a decisao do ciclo de renovacao.
 */
@Configuration
public class WebhookConfig {

  /**
   * Cria o validador HMAC usado pelo endpoint de webhook.
   *
   * @param webhookSecret chave compartilhada com o gateway
   * @param environment ambiente ativo do Spring
   * @return validador HMAC configurado
   */
  @Bean
  public HmacSignatureValidator hmacSignatureValidator(
      @Value("${app.gateway.webhook-secret}") String webhookSecret, Environment environment) {
    if (!isDev(environment)
        && (webhookSecret == null
            || webhookSecret.isBlank()
            || "mock-webhook-secret".equals(webhookSecret))) {
      throw new IllegalStateException(
          "app.gateway.webhook-secret deve ser definida fora do perfil dev");
    }
    return new HmacSignatureValidator(webhookSecret);
  }

  /**
   * Cria o command de processamento de webhook.
   *
   * @param hmacValidator validador da assinatura HMAC
   * @param eventoRepository repositorio de eventos processados (dedup)
   * @param pagamentoRenovacaoRepository repositorio de pagamentos de renovacao, usado no despacho
   * @param gatewayClient client de consulta de status no gateway
   * @param normalizador normalizador de status
   * @param registrarResultado command que registra o resultado do webhook na outbox
   * @return o command configurado
   */
  @Bean
  public ProcessarWebhookPagamento processarWebhookPagamento(
      HmacSignatureValidator hmacValidator,
      WebhookEventoProcessadoRepository eventoRepository,
      PagamentoRenovacaoRepository pagamentoRenovacaoRepository,
      GatewayPagamentoClient gatewayClient,
      NormalizadorStatus normalizador,
      RegistrarResultadoWebhook registrarResultado) {
    return new ProcessarWebhookPagamento(
        hmacValidator,
        eventoRepository,
        pagamentoRenovacaoRepository,
        gatewayClient,
        normalizador,
        registrarResultado);
  }

  /**
   * Cria o command que registra o resultado do webhook na outbox.
   *
   * @param eventoRepository repositorio de eventos processados (dedup)
   * @param cobrancaRepository repositorio de cobrancas
   * @param processarRenovacao command que decide o resultado de uma cobranca de renovacao
   * @param outboxRepository repositorio da outbox
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   * @return o command configurado
   */
  @Bean
  public RegistrarResultadoWebhook registrarResultadoWebhook(
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      ProcessarWebhookRenovacao processarRenovacao,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    return new RegistrarResultadoWebhook(
        eventoRepository, cobrancaRepository, processarRenovacao, outboxRepository, objectMapper);
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

  private static boolean isDev(Environment environment) {
    return Arrays.asList(environment.getActiveProfiles()).contains("dev");
  }
}
