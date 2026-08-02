package com.globo.pagamento.webhook.api;

import com.globo.pagamento.webhook.ProcessarWebhookPagamento;
import com.globo.pagamento.webhook.WebhookInvalidoException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Endpoint que recebe a notificacao de pagamento enviada pelo gateway.
 *
 * <p>O corpo e lido como bytes brutos para a validacao HMAC, depois parseado para extrair o {@code
 * paymentId} e o {@code externalReference}. A autenticacao e a assinatura HMAC, validada no
 * command.
 */
@RestController
@RequestMapping("/webhooks/payments")
public class WebhookPagamentoController {

  private final ObjectMapper objectMapper;
  private final ProcessarWebhookPagamento command;

  /**
   * Cria o controller.
   *
   * @param objectMapper mapeador JSON para parsear o corpo da notificacao
   * @param command command que processa a notificacao
   */
  public WebhookPagamentoController(ObjectMapper objectMapper, ProcessarWebhookPagamento command) {
    this.objectMapper = objectMapper;
    this.command = command;
  }

  /**
   * Recebe e processa a notificacao de pagamento.
   *
   * <p>O event id do header precisa coincidir com o {@code id} do corpo assinado: e por ele que a
   * deduplicacao ancora, e um header divergente indica reutilizacao de um corpo capturado. A
   * divergencia e rejeitada como assinatura invalida.
   *
   * @param corpo corpo bruto da requisicao
   * @param eventId identificador unico do evento (header {@code X-Mock-Event-Id})
   * @param assinatura valor do header {@code X-Mock-Signature}
   * @return confirmacao {@code 200} com o eventId processado
   * @throws WebhookInvalidoException se o event id do header divergir do id do corpo
   */
  @PostMapping
  public ResponseEntity<WebhookAck> receber(
      @RequestBody byte[] corpo,
      @RequestHeader("X-Mock-Event-Id") UUID eventId,
      @RequestHeader("X-Mock-Signature") String assinatura) {
    WebhookEvent evento = objectMapper.readValue(corpo, WebhookEvent.class);
    if (!evento.id().equals(eventId)) {
      throw new WebhookInvalidoException();
    }
    UUID processado =
        command.processar(
            corpo, eventId, evento.externalReference(), evento.paymentId(), assinatura);
    return ResponseEntity.ok(new WebhookAck(true, processado));
  }
}
