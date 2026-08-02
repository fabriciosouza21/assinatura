package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Cobranca;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.messaging.event.StatusPagamento;
import com.globo.pagamento.outbox.OutboxEvent;
import com.globo.pagamento.outbox.OutboxRepository;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste unitario do command {@link RegistrarResultadoWebhook}.
 *
 * <p>Verifica que a decisao do webhook, a gravacao na outbox e o registro de deduplicacao acontecem
 * na mesma transacao, nos dois ramos: adesao (cobranca + evento {@code PagamentoStatusAtualizado})
 * e renovacao (delegada a {@link ProcessarWebhookRenovacao}).
 */
@ExtendWith(MockitoExtension.class)
class RegistrarResultadoWebhookTest {

  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000021";
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private WebhookEventoProcessadoRepository eventoRepository;
  @Mock private CobrancaRepository cobrancaRepository;
  @Mock private ProcessarWebhookRenovacao processarRenovacao;
  @Mock private OutboxRepository outboxRepository;

  private RegistrarResultadoWebhook registrar;

  @BeforeEach
  void setUp() {
    registrar =
        new RegistrarResultadoWebhook(
            eventoRepository, cobrancaRepository, processarRenovacao, outboxRepository, jsonMapper);
  }

  @Test
  @DisplayName("Deve registrar adesao atualizando cobranca, gravando outbox e dedup na mesma ordem")
  void deveRegistrarAdesaoComCobrancaOutboxEdedup() {
    UUID eventId = EVENT_ID;
    UUID assinaturaId = UUID.fromString(ASSINATURA_ID);
    UUID paymentId = UUID.fromString(PAYMENT_ID);
    Cobranca cobranca = new Cobranca(ASSINATURA_ID, PAYMENT_ID, StatusCobranca.PENDING);
    when(cobrancaRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(cobranca));

    registrar.registrar(
        eventId, assinaturaId, paymentId, StatusPagamento.APPROVED, Optional.empty());

    assertThat(cobranca.getStatus())
        .as("Cobranca da adesao marcada como aprovada")
        .isEqualTo(StatusCobranca.APPROVED);
    InOrder ordem = Mockito.inOrder(cobrancaRepository, outboxRepository, eventoRepository);
    ordem.verify(cobrancaRepository).save(cobranca);
    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    ordem.verify(outboxRepository).save(outboxCaptor.capture());
    ArgumentCaptor<WebhookEventoProcessado> dedupCaptor =
        ArgumentCaptor.forClass(WebhookEventoProcessado.class);
    ordem.verify(eventoRepository).save(dedupCaptor.capture());

    OutboxEvent evento = outboxCaptor.getValue();
    assertThat(evento.getEventId()).as("EventId do webhook e a chave da outbox").isEqualTo(eventId);
    assertThat(evento.getAggregateType()).as("Tipo do agregado da adesao").isEqualTo("Cobranca");
    assertThat(evento.getAggregateId()).as("AggregateId da adesao").isEqualTo(assinaturaId);
    assertThat(evento.getEventType())
        .as("Evento de status da adesao")
        .isEqualTo("PagamentoStatusAtualizado");
    assertThat(evento.getPayload())
        .as("Payload com assinatura, status e pagamento")
        .contains("\"assinaturaId\":\"" + ASSINATURA_ID + "\"")
        .contains("\"status\":\"APPROVED\"")
        .contains("\"paymentId\":\"" + PAYMENT_ID + "\"");
    assertThat(dedupCaptor.getValue().getEventId())
        .as("Dedup registrada sob o eventId do webhook")
        .isEqualTo(eventId);
    assertThat(dedupCaptor.getValue().getAssinaturaId())
        .as("Dedup registrada sob a assinatura da adesao")
        .isEqualTo(assinaturaId);
  }

  @Test
  @DisplayName("Deve registrar o evento mesmo sem cobranca correlacionada")
  void deveRegistrarEventoMesmoSemCobrancaCorrelacionada() {
    UUID eventId = EVENT_ID;
    UUID assinaturaId = UUID.fromString(ASSINATURA_ID);
    when(cobrancaRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());

    registrar.registrar(
        eventId,
        assinaturaId,
        UUID.fromString(PAYMENT_ID),
        StatusPagamento.APPROVED,
        Optional.empty());

    verify(cobrancaRepository, never()).save(any());
    verify(outboxRepository).save(any());
    verify(eventoRepository).save(any());
  }

  @Test
  @DisplayName("Deve delegar a decisao de renovacao e gravar dedup sem gravar outbox")
  void deveDelegarRenovacaoEgravarDedup() {
    UUID eventId = EVENT_ID;
    UUID renovacaoId = UUID.fromString(RENOVACAO_ID);
    PagamentoRenovacao pagamento = pagamentoRenovacao();

    registrar.registrar(
        eventId,
        renovacaoId,
        UUID.fromString(PAYMENT_ID),
        StatusPagamento.APPROVED,
        Optional.of(pagamento));

    verify(processarRenovacao).decidir(pagamento, eventId, PAYMENT_ID, StatusPagamento.APPROVED);
    verify(outboxRepository, never()).save(any());
    ArgumentCaptor<WebhookEventoProcessado> dedupCaptor =
        ArgumentCaptor.forClass(WebhookEventoProcessado.class);
    verify(eventoRepository).save(dedupCaptor.capture());
    assertThat(dedupCaptor.getValue().getAssinaturaId())
        .as("Dedup registrada sob a assinatura renovada, nao sob a renovacao")
        .isEqualTo(UUID.fromString(ASSINATURA_ID));
  }

  @Test
  @DisplayName("Deve propagar falha na gravacao da outbox sem registrar dedup")
  void devePropagarFalhaDaOutboxSemGravarDedup() {
    UUID eventId = EVENT_ID;
    when(cobrancaRepository.findByPaymentId(any())).thenReturn(Optional.empty());
    when(outboxRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("uq_outbox_event_id"));

    assertThatThrownBy(
            () ->
                registrar.registrar(
                    eventId,
                    UUID.fromString(ASSINATURA_ID),
                    UUID.fromString(PAYMENT_ID),
                    StatusPagamento.APPROVED,
                    Optional.empty()))
        .as("Conflito concorrente de eventId na outbox estoura a transacao")
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(eventoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve propagar decisao indisponivel de renovacao sem registrar dedup")
  void devePropagarDecisaoIndisponivelSemGravarDedup() {
    UUID eventId = EVENT_ID;
    PagamentoRenovacao pagamento = pagamentoRenovacao();
    doThrow(new DecisaoRenovacaoIndisponivelException())
        .when(processarRenovacao)
        .decidir(any(), any(), any(), any());

    assertThatThrownBy(
            () ->
                registrar.registrar(
                    eventId,
                    UUID.fromString(RENOVACAO_ID),
                    UUID.fromString(PAYMENT_ID),
                    StatusPagamento.APPROVED,
                    Optional.of(pagamento)))
        .as("Sem dedup o reenvio do gateway volta a processar")
        .isInstanceOf(DecisaoRenovacaoIndisponivelException.class);
    verify(eventoRepository, never()).save(any());
  }

  private PagamentoRenovacao pagamentoRenovacao() {
    return new PagamentoRenovacao(
        RENOVACAO_ID, ASSINATURA_ID, Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
