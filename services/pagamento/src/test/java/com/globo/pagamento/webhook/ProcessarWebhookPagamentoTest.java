package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Cobranca;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste unitario do command {@link ProcessarWebhookPagamento}.
 *
 * <p>Verifica a orquestracao do webhook: validacao HMAC, dedup por eventId, consulta de status no
 * gateway, normalizacao, publicacao publish-then-ACK e gravacao do eventId apos o publish
 * confirmado.
 */
@ExtendWith(MockitoExtension.class)
class ProcessarWebhookPagamentoTest {

  private static final String EVENT_ID = "00000000-0000-0000-0000-000000000099";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000021";
  private static final byte[] CORPO = "corpo-do-webhook".getBytes();

  @SuppressWarnings("NullAway")
  private static final CompletableFuture<SendResult<String, String>> PUBLICADO =
      CompletableFuture.completedFuture(null);

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private HmacSignatureValidator hmacValidator;
  @Mock private WebhookEventoProcessadoRepository eventoRepository;
  @Mock private CobrancaRepository cobrancaRepository;
  @Mock private GatewayPagamentoClient gatewayClient;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private NormalizadorStatus normalizador;
  private ProcessarWebhookPagamento command;

  @BeforeEach
  void setUp() {
    normalizador = new NormalizadorStatus();
    command =
        new ProcessarWebhookPagamento(
            jsonMapper,
            hmacValidator,
            eventoRepository,
            cobrancaRepository,
            gatewayClient,
            normalizador,
            kafkaTemplate,
            "pagamento-status-atualizado");
  }

  @Test
  @DisplayName("Deve publicar evento e gravar eventId quando o webhook e valido")
  void devePublicarEgravarEventIdQuandoValido() throws Exception {
    UUID eventId = UUID.fromString(EVENT_ID);
    UUID assinaturaId = UUID.fromString(ASSINATURA_ID);
    UUID paymentId = UUID.fromString(PAYMENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(PUBLICADO);

    UUID publicado = command.processar(CORPO, eventId, assinaturaId, paymentId, "sha256=abc");

    assertThat(publicado).as("EventId publicado").isEqualTo(eventId);
    verify(kafkaTemplate).send(eq("pagamento-status-atualizado"), eq(ASSINATURA_ID), any());
    verify(eventoRepository).save(any());
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

    verify(eventoRepository, never()).save(any());
    verify(kafkaTemplate, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("Deve lancar excecao de publicacao indisponivel quando o Kafka falha")
  void deveLancarQuandoPublicacaoFalha() throws Exception {
    when(hmacValidator.valido(any(), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(any())).thenReturn(false);
    when(gatewayClient.consultarStatus(any())).thenReturn(StatusGateway.APPROVED);
    CompletableFuture<SendResult<String, String>> falha = new CompletableFuture<>();
    falha.completeExceptionally(new ExecutionException(new RuntimeException("kafka fora do ar")));
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(falha);

    assertThatThrownBy(
            () ->
                command.processar(
                    CORPO,
                    UUID.fromString(EVENT_ID),
                    UUID.fromString(ASSINATURA_ID),
                    UUID.fromString(PAYMENT_ID),
                    "sha256=abc"))
        .as("Falha de publicacao sinalizada")
        .isInstanceOf(PublicacaoIndisponivelException.class);

    verify(eventoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve ignorar reenvio de eventId ja processado sem republicar")
  void deveIgnorarReenvioSemRepublicar() {
    when(hmacValidator.valido(any(), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(UUID.fromString(EVENT_ID))).thenReturn(true);

    UUID publicado =
        command.processar(
            CORPO,
            UUID.fromString(EVENT_ID),
            UUID.fromString(ASSINATURA_ID),
            UUID.fromString(PAYMENT_ID),
            "sha256=abc");

    assertThat(publicado).as("EventId de reenvio reconhecido").isEqualTo(UUID.fromString(EVENT_ID));
    verify(gatewayClient, never()).consultarStatus(any());
    verify(kafkaTemplate, never()).send(any(), any(), any());
    verify(eventoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve marcar a cobranca existente com o status normalizado")
  void deveMarcarCobrancaComStatusNormalizado() throws Exception {
    Cobranca cobranca = new Cobranca(ASSINATURA_ID, PAYMENT_ID, StatusCobranca.PENDING);
    when(hmacValidator.valido(any(), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(any())).thenReturn(false);
    when(gatewayClient.consultarStatus(any())).thenReturn(StatusGateway.APPROVED);
    when(cobrancaRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.of(cobranca));
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    command.processar(
        CORPO,
        UUID.fromString(EVENT_ID),
        UUID.fromString(ASSINATURA_ID),
        UUID.fromString(PAYMENT_ID),
        "sha256=abc");

    assertThat(cobranca.getStatus())
        .as("Cobranca marcada como aprovada")
        .isEqualTo(StatusCobranca.APPROVED);
    verify(cobrancaRepository).save(cobranca);
  }

  @Test
  @DisplayName("Deve tratar duplicidade concorrente de eventId no save como ja processado")
  void deveTratarDuplicidadeConcorrenteNoSaveComoJaProcessado() {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(any())).thenReturn(StatusGateway.APPROVED);
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(PUBLICADO);
    when(eventoRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("uq_webhook_evento_processado_event_id"));

    UUID publicado =
        command.processar(
            CORPO,
            eventId,
            UUID.fromString(ASSINATURA_ID),
            UUID.fromString(PAYMENT_ID),
            "sha256=abc");

    assertThat(publicado).as("EventId tratado como ja processado").isEqualTo(eventId);
  }

  @Test
  @DisplayName("Deve publicar mesmo quando nao ha cobranca correlacionada")
  void devePublicarMesmoSemCobrancaCorrelacionada() throws Exception {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(cobrancaRepository.findByPaymentId(PAYMENT_ID)).thenReturn(Optional.empty());
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(PUBLICADO);

    UUID publicado =
        command.processar(
            CORPO,
            eventId,
            UUID.fromString(ASSINATURA_ID),
            UUID.fromString(PAYMENT_ID),
            "sha256=abc");

    assertThat(publicado).as("EventId publicado mesmo sem cobranca").isEqualTo(eventId);
    verify(cobrancaRepository, never()).save(any());
    verify(eventoRepository).save(any());
  }

  @Test
  @DisplayName("Deve gravar o eventId somente apos a publicacao no Kafka confirmar")
  void deveGravarEventIdAposPublicacaoConfirmada() throws Exception {
    UUID eventId = UUID.fromString(EVENT_ID);
    when(hmacValidator.valido(eq(CORPO), any())).thenReturn(true);
    when(eventoRepository.existsByEventId(eventId)).thenReturn(false);
    when(gatewayClient.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(PUBLICADO);

    command.processar(
        CORPO, eventId, UUID.fromString(ASSINATURA_ID), UUID.fromString(PAYMENT_ID), "sha256=abc");

    InOrder ordem = inOrder(kafkaTemplate, eventoRepository);
    ordem.verify(kafkaTemplate).send(eq("pagamento-status-atualizado"), eq(ASSINATURA_ID), any());
    ordem.verify(eventoRepository).save(any());
  }
}
