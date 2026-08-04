package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.shared.contrato.StatusPagamento;
import com.globo.pagamento.webhook.api.HmacSignatureValidator;
import com.globo.pagamento.webhook.idempotencia.WebhookEventoProcessadoRepository;
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
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do command {@link ProcessarWebhookPagamento}.
 *
 * <p>Verifica a orquestracao do webhook: validacao HMAC, dedup por eventId, consulta de status no
 * gateway, normalizacao e delegacao do registro (decisao + outbox + dedup) a {@link
 * RegistrarResultadoWebhook}. O ack do Kafka sai do caminho do cliente e fica com o publisher da
 * outbox.
 */
@ExtendWith(MockitoExtension.class)
class ProcessarWebhookPagamentoTest {

  private static final String EVENT_ID = "00000000-0000-0000-0000-000000000099";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000021";
  private static final byte[] CORPO = "corpo-do-webhook".getBytes();

  @Mock private HmacSignatureValidator hmacValidator;
  @Mock private WebhookEventoProcessadoRepository eventoRepository;
  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  @Mock private GatewayPagamentoClient gatewayClient;
  @Mock private RegistrarResultadoWebhook registrarResultado;

  private NormalizadorStatus normalizador;
  private ProcessarWebhookPagamento command;

  @BeforeEach
  void setUp() {
    normalizador = new NormalizadorStatus();
    command =
        new ProcessarWebhookPagamento(
            hmacValidator,
            eventoRepository,
            pagamentoRenovacaoRepository,
            gatewayClient,
            normalizador,
            registrarResultado);
  }

  @Test
  @DisplayName("Deve delegar o registro quando o webhook e valido")
  void deveDelegarRegistroQuandoWebhookValido() {
    UUID eventId = UUID.fromString(EVENT_ID);
    UUID assinaturaId = UUID.fromString(ASSINATURA_ID);
    UUID paymentId = UUID.fromString(PAYMENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(ASSINATURA_ID))
        .thenReturn(Optional.empty());

    UUID processado = command.processar(CORPO, eventId, assinaturaId, paymentId, "sha256=abc");

    assertThat(processado).as("EventId processado").isEqualTo(eventId);
    verify(registrarResultado)
        .registrar(
            eq(eventId),
            eq(assinaturaId),
            eq(paymentId),
            eq(StatusPagamento.APPROVED),
            eq(Optional.empty()));
  }

  @Test
  @DisplayName("Deve lancar excecao de webhook invalido quando a assinatura HMAC nao bate")
  void deveLancarQuandoAssinaturaNaoBate() {
    when(hmacValidator.valido(any(), any())).thenReturn(false);

    assertThatThrownBy(
            () ->
                command.processar(
                    CORPO,
                    UUID.fromString(EVENT_ID),
                    UUID.fromString(ASSINATURA_ID),
                    UUID.fromString(PAYMENT_ID),
                    "sha256=invalida"))
        .as("Assinatura HMAC invalida rejeitada")
        .isInstanceOf(WebhookInvalidoException.class);

    verify(registrarResultado, never()).registrar(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Deve lancar excecao de publicacao indisponivel quando a consulta ao gateway falha")
  void deveLancarQuandoConsultaAoGatewayFalha() {
    when(hmacValidator.valido(any(), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(any())).thenReturn(false);
    when(gatewayClient.consultarStatus(any()))
        .thenThrow(new RuntimeException("gateway fora do ar"));

    assertThatThrownBy(
            () ->
                command.processar(
                    CORPO,
                    UUID.fromString(EVENT_ID),
                    UUID.fromString(ASSINATURA_ID),
                    UUID.fromString(PAYMENT_ID),
                    "sha256=abc"))
        .as("Falha de consulta sinalizada como publicacao indisponivel")
        .isInstanceOf(PublicacaoIndisponivelException.class);

    verify(registrarResultado, never()).registrar(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Deve ignorar reenvio de eventId ja processado sem delegar registro")
  void deveIgnorarReenvioSemDelegar() {
    when(hmacValidator.valido(any(), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(UUID.fromString(EVENT_ID))).thenReturn(true);

    UUID processado =
        command.processar(
            CORPO,
            UUID.fromString(EVENT_ID),
            UUID.fromString(ASSINATURA_ID),
            UUID.fromString(PAYMENT_ID),
            "sha256=abc");

    assertThat(processado)
        .as("EventId de reenvio reconhecido")
        .isEqualTo(UUID.fromString(EVENT_ID));
    verify(gatewayClient, never()).consultarStatus(any());
    verify(registrarResultado, never()).registrar(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Deve delegar a decisao de renovacao quando a referencia externa resolve")
  void deveDelegarRegistroDeRenovacaoQuandoReferenciaResolve() {
    UUID eventId = UUID.fromString(EVENT_ID);
    final UUID renovacaoId = UUID.fromString(RENOVACAO_ID);
    final UUID paymentId = UUID.fromString(PAYMENT_ID);
    PagamentoRenovacao pagamento = pagamentoRenovacao();
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamento));

    command.processar(CORPO, eventId, renovacaoId, paymentId, "sha256=abc");

    verify(registrarResultado)
        .registrar(
            eq(eventId),
            eq(renovacaoId),
            eq(paymentId),
            eq(StatusPagamento.APPROVED),
            eq(Optional.of(pagamento)));
  }

  @Test
  @DisplayName("Deve propagar decisao indisponivel sem delegar dedup")
  void devePropagarDecisaoIndisponivel() {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoRenovacao()));
    doThrow(new DecisaoRenovacaoIndisponivelException())
        .when(registrarResultado)
        .registrar(any(), any(), any(), any(), any());

    assertThatThrownBy(
            () ->
                command.processar(
                    CORPO,
                    eventId,
                    UUID.fromString(RENOVACAO_ID),
                    UUID.fromString(PAYMENT_ID),
                    "sha256=abc"))
        .as("Aguardar o reenvio exige que o event id nao entre na deduplicacao")
        .isInstanceOf(DecisaoRenovacaoIndisponivelException.class);
  }

  @Test
  @DisplayName("Deve consultar o status no gateway antes de delegar o registro")
  void deveConsultarStatusAntesDeRegistrar() {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(ASSINATURA_ID))
        .thenReturn(Optional.empty());

    command.processar(
        CORPO, eventId, UUID.fromString(ASSINATURA_ID), UUID.fromString(PAYMENT_ID), "sha256=abc");

    InOrder ordem = inOrder(gatewayClient, registrarResultado);
    ordem.verify(gatewayClient).consultarStatus(PAYMENT_ID);
    ordem.verify(registrarResultado).registrar(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Deve resolver a renovacao pelo externalReference antes de delegar")
  void deveResolverRenovacaoPeloExternalReference() {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoRenovacao()));

    command.processar(
        CORPO, eventId, UUID.fromString(RENOVACAO_ID), UUID.fromString(PAYMENT_ID), "sha256=abc");

    ArgumentCaptor<Optional<PagamentoRenovacao>> renovacaoCaptor =
        ArgumentCaptor.forClass(Optional.class);
    verify(registrarResultado).registrar(any(), any(), any(), any(), renovacaoCaptor.capture());
    assertThat(renovacaoCaptor.getValue().isPresent())
        .as("Renovacao resolvida e delegada ao registro")
        .isTrue();
  }

  private PagamentoRenovacao pagamentoRenovacao() {
    return new PagamentoRenovacao(
        RENOVACAO_ID, ASSINATURA_ID, Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
