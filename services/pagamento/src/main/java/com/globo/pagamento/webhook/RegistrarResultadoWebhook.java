package com.globo.pagamento.webhook;

import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.messaging.event.PagamentoStatusAtualizado;
import com.globo.pagamento.messaging.event.StatusPagamento;
import com.globo.pagamento.outbox.OutboxEvent;
import com.globo.pagamento.outbox.OutboxRepository;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Command que registra o resultado de uma notificacao de webhook na mesma transacao.
 *
 * <p>No ramo de adesao, atualiza a cobranca correlacionada e grava {@link
 * PagamentoStatusAtualizado} na outbox. No ramo de renovacao, delega a decisao a {@link
 * ProcessarWebhookRenovacao}, que grava o resultado na outbox quando o ciclo encerra. Em ambos os
 * ramos, o {@code eventId} e registrado como processado no fim da transacao: se ela voltar, o
 * evento e a deduplicacao nao existem, e o reenvio do gateway volta a processar.
 */
public class RegistrarResultadoWebhook {

  private final WebhookEventoProcessadoRepository eventoRepository;
  private final CobrancaRepository cobrancaRepository;
  private final ProcessarWebhookRenovacao processarRenovacao;
  private final OutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;

  /**
   * Cria o command com suas dependencias.
   *
   * @param eventoRepository repositorio de eventos processados (dedup por eventId)
   * @param cobrancaRepository repositorio de cobrancas
   * @param processarRenovacao command que decide o resultado de uma cobranca de renovacao
   * @param outboxRepository repositorio da outbox
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   */
  public RegistrarResultadoWebhook(
      WebhookEventoProcessadoRepository eventoRepository,
      CobrancaRepository cobrancaRepository,
      ProcessarWebhookRenovacao processarRenovacao,
      OutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this.eventoRepository = eventoRepository;
    this.cobrancaRepository = cobrancaRepository;
    this.processarRenovacao = processarRenovacao;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Registra o resultado da cobranca e o {@code eventId} como processado, tudo na mesma transacao.
   *
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id})
   * @param externalReference referencia externa da cobranca: o {@code renovacaoId} numa renovacao,
   *     o {@code assinaturaId} numa adesao
   * @param paymentId identificador da cobranca no gateway
   * @param status status oficial do gateway ja normalizado
   * @param renovacao pagamento de renovacao correlacionado ao {@code externalReference}, se houver
   * @throws DecisaoRenovacaoIndisponivelException se a decisao de renovacao nao for possivel
   */
  @Transactional
  public void registrar(
      UUID eventId,
      UUID externalReference,
      UUID paymentId,
      StatusPagamento status,
      Optional<PagamentoRenovacao> renovacao) {
    if (renovacao.isPresent()) {
      PagamentoRenovacao pagamento = renovacao.get();
      UUID assinaturaId = UUID.fromString(pagamento.getAssinaturaId());
      processarRenovacao.decidir(pagamento, eventId, paymentId.toString(), status);
      eventoRepository.save(new WebhookEventoProcessado(eventId, assinaturaId));
      return;
    }
    atualizarCobranca(paymentId, status);
    gravarOutbox(eventId, externalReference, paymentId, status);
    eventoRepository.save(new WebhookEventoProcessado(eventId, externalReference));
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

  private void gravarOutbox(
      UUID eventId, UUID assinaturaId, UUID paymentId, StatusPagamento status) {
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(eventId, Instant.now(), assinaturaId, status, paymentId);
    String payload = objectMapper.writeValueAsString(evento);
    outboxRepository.save(
        OutboxEvent.criar(eventId, "Cobranca", assinaturaId, "PagamentoStatusAtualizado", payload));
  }
}
