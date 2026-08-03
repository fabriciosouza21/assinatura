package com.globo.pagamento.shared.kafka.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

/**
 * Teste unitario do {@link ReplayDlqConsumer}.
 *
 * <p>Verifica a republicacao da mensagem da DLQ no topico original (mesma chave e payload bruta,
 * com marcador de republicacao), o descarte com confirmacao quando o topico original e ilegivel e o
 * adiamento de bounces dentro do intervalo configurado.
 */
@ExtendWith(MockitoExtension.class)
class ReplayDlqConsumerTest {

  private static final String TOPICO_DLQ = "pagamento-status-atualizado-dlq";
  private static final String TOPICO_ORIGINAL = "pagamento-status-atualizado";
  private static final String CHAVE = "550e8400-e29b-41d4-a716-446655440000";
  private static final String PAYLOAD = "{\"tipo\":\"APROVADO\"}";
  private static final long INTERVALO_MS = 3600000L;

  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Mock private Acknowledgment acknowledgment;

  private ReplayDlqConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new ReplayDlqConsumer(kafkaTemplate, new ReplayDlqProperties(INTERVALO_MS));
  }

  @Test
  @DisplayName(
      "Deve republicar no topico original com a mesma chave e payload e marcar a republicacao")
  void deveRepublicarNoTopicoOriginalComMesmaChaveComPayload() throws Exception {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)));
    CompletableFuture<SendResult<String, String>> futuro = CompletableFuture.completedFuture(null);

    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(futuro);

    consumer.republicar(registro, acknowledgment);

    ArgumentCaptor<ProducerRecord<String, String>> registroCaptor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(registroCaptor.capture());
    verify(acknowledgment).acknowledge();
    ProducerRecord<String, String> publicado = registroCaptor.getValue();
    assertThat(publicado.topic()).as("Topico original do replay").isEqualTo(TOPICO_ORIGINAL);
    assertThat(publicado.key()).as("Chave preservada no replay").isEqualTo(CHAVE);
    assertThat(publicado.value()).as("Payload bruta preservada no replay").isEqualTo(PAYLOAD);
    assertThat(publicado.headers().lastHeader(ReplayDlqConsumer.HEADER_REPUBLICACAO))
        .as("Marcador de republicacao presente no envio")
        .isNotNull();
  }

  @Test
  @DisplayName("Deve descartar e confirmar quando o header do topico original esta ausente")
  void deveDescartarQuandoHeaderAusente() {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);

    consumer.republicar(registro, acknowledgment);

    verifyNoInteractions(kafkaTemplate);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("Deve descartar e confirmar quando o header do topico original esta ilegivel")
  void deveDescartarQuandoHeaderIlegivel() {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, "".getBytes(StandardCharsets.UTF_8)));

    consumer.republicar(registro, acknowledgment);

    verifyNoInteractions(kafkaTemplate);
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("Deve adiar o bounce com nack quando a mensagem foi republicada dentro do intervalo")
  void deveAdiarBounceComNack() {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)));
    long agora = System.currentTimeMillis();
    registro
        .headers()
        .add(
            new RecordHeader(
                ReplayDlqConsumer.HEADER_REPUBLICACAO,
                Long.toString(agora - 1000L).getBytes(StandardCharsets.UTF_8)));

    consumer.republicar(registro, acknowledgment);

    verifyNoInteractions(kafkaTemplate);
    verify(acknowledgment).nack(any(Duration.class));
  }

  @Test
  @DisplayName("Deve republicar quando o marcador ja expirou o intervalo")
  void deveRepublicarQuandoMarcadorExpirado() throws Exception {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)));
    long agora = System.currentTimeMillis();
    registro
        .headers()
        .add(
            new RecordHeader(
                ReplayDlqConsumer.HEADER_REPUBLICACAO,
                Long.toString(agora - INTERVALO_MS - 1000L).getBytes(StandardCharsets.UTF_8)));
    CompletableFuture<SendResult<String, String>> futuro = CompletableFuture.completedFuture(null);

    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(futuro);

    consumer.republicar(registro, acknowledgment);

    verify(kafkaTemplate).send(any(ProducerRecord.class));
    verify(acknowledgment).acknowledge();
  }

  @Test
  @DisplayName("Deve propagar a falha de envio para o error handler sem confirmar")
  void devePropagarFalhaDeEnvio() throws Exception {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)));
    CompletableFuture<SendResult<String, String>> futuro =
        CompletableFuture.failedFuture(new RuntimeException("broker indisponivel"));
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(futuro);

    assertThatThrownBy(() -> consumer.republicar(registro, acknowledgment))
        .as("Falha de envio propagada ao error handler")
        .isInstanceOf(IllegalStateException.class);

    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  @DisplayName("Deve propagar a falha de envio com o causador preservado")
  void devePropagarFalhaDeEnvioComCausadorPreservado() throws Exception {
    var registro = new ConsumerRecord<>(TOPICO_DLQ, 0, 0L, CHAVE, PAYLOAD);
    registro
        .headers()
        .add(
            new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC, TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)));
    RuntimeException causa = new RuntimeException("broker indisponivel");
    CompletableFuture<SendResult<String, String>> futuro = CompletableFuture.failedFuture(causa);
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(futuro);

    assertThatThrownBy(() -> consumer.republicar(registro, acknowledgment))
        .as("Causador da falha de envio preservado")
        .hasCause(causa);
  }
}
