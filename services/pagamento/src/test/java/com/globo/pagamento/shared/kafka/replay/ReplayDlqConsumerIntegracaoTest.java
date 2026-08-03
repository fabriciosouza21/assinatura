package com.globo.pagamento.shared.kafka.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.globo.pagamento.cobranca.Cobranca;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.shared.contrato.AssinaturaSolicitada;
import com.globo.pagamento.shared.contrato.Plano;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Teste de integracao do replay da DLQ de consumer contra o broker EmbeddedKafka e o Postgres real.
 *
 * <p>Com o replay habilitado ({@code app.kafka.replay.auto-startup=true}) e intervalo curto: (1)
 * publica um evento valido no topico {@code assinatura-solicitada-dlq} e verifica que ele e
 * republicado no topico original com a mesma chave e payload (a correlacao de cobranca ja existente
 * torna o reprocessamento idempotente, sem chamada ao gateway); (2) publica um registro com
 * marcador de republicacao recente e verifica que a republicacao so ocorre apos o intervalo
 * configurado, sem ciclo rapido.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {
      "assinatura-solicitada",
      "assinatura-solicitada-dlq",
      "renovacao-solicitada-dlq",
      "cancelamento-agendado-dlq",
      "assinatura-cancelada-dlq"
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "app.kafka.replay.auto-startup=true",
      "app.kafka.replay.backoff-interval-ms=5000",
      "app.renovacao.scheduler-intervalo-ms=3600000",
      "app.renovacao.scheduler-delay-inicial-ms=3600000",
    })
class ReplayDlqConsumerIntegracaoTest {

  private static final String TOPICO_ORIGINAL = "assinatura-solicitada";
  private static final String TOPICO_DLQ = "assinatura-solicitada-dlq";
  private static final String ASSINATURA_ID = "66666666-6666-6666-6666-666666666666";
  private static final String EVENT_ID = "55555555-5555-5555-5555-555555555555";

  @Autowired private CobrancaRepository cobrancaRepository;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;

  @Test
  @DisplayName("Deve republicar a mensagem da DLQ no topico original com a mesma chave e payload")
  void deveRepublicarDlqNoTopicoOriginal() throws Exception {
    cobrancaRepository.save(new Cobranca(ASSINATURA_ID, "payment-123", StatusCobranca.PENDING));
    AssinaturaSolicitada evento =
        new AssinaturaSolicitada(
            UUID.fromString(EVENT_ID),
            Instant.parse("2026-07-30T12:00:00Z"),
            UUID.fromString(ASSINATURA_ID),
            UUID.fromString("44444444-4444-4444-4444-444444444444"),
            Plano.BASICO,
            new BigDecimal("19.90"));
    String payload = objectMapper.writeValueAsString(evento);

    try (KafkaConsumer<String, String> observador = consumidor(TOPICO_ORIGINAL)) {
      observador.subscribe(List.of(TOPICO_ORIGINAL));
      observador.poll(Duration.ofSeconds(1));

      publicarNaDlq(payload);

      List<ConsumerRecord<String, String>> republicadas = new ArrayList<>();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                coletar(observador, republicadas);
                assertThat(republicadas)
                    .as("Topico original recebeu a mensagem da DLQ")
                    .anyMatch(registro -> registro.value().contains(EVENT_ID));
              });
      ConsumerRecord<String, String> republicada =
          republicadas.stream()
              .filter(registro -> registro.value().contains(EVENT_ID))
              .findFirst()
              .orElseThrow();
      assertThat(republicada.key()).as("Chave preservada no replay").isEqualTo(ASSINATURA_ID);
      assertThat(republicada.headers().lastHeader(ReplayDlqConsumer.HEADER_REPUBLICACAO))
          .as("Marcador de republicacao anexado")
          .isNotNull();
    }
  }

  @Test
  @DisplayName("Deve adiar o bounce por um intervalo antes de republicar de novo")
  void deveAdiarBounceComIntervalo() throws Exception {
    String payload = "{isto-nao-e-json-2";
    try (KafkaConsumer<String, String> observador = consumidor(TOPICO_ORIGINAL)) {
      observador.subscribe(List.of(TOPICO_ORIGINAL));
      observador.poll(Duration.ofSeconds(1));

      publicarNaDlqComMarcadorRecent(payload);

      List<ConsumerRecord<String, String>> republicadas = new ArrayList<>();
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () -> {
                coletar(observador, republicadas);
                return contar(republicadas, "isto-nao-e-json-2") >= 1;
              });

      long fimDaJanela = System.currentTimeMillis() + 3500;
      while (System.currentTimeMillis() < fimDaJanela) {
        coletar(observador, republicadas);
      }
      assertThat(contar(republicadas, "isto-nao-e-json-2"))
          .as("Sem segunda republicacao dentro do intervalo")
          .isEqualTo(1);

      await()
          .atMost(Duration.ofSeconds(15))
          .until(
              () -> {
                coletar(observador, republicadas);
                return contar(republicadas, "isto-nao-e-json-2") >= 2;
              });
    }
  }

  private void publicarNaDlq(String payload) throws Exception {
    kafkaTemplate
        .send(
            new ProducerRecord<>(
                TOPICO_DLQ,
                null,
                ASSINATURA_ID,
                payload,
                List.of(
                    new RecordHeader(
                        KafkaHeaders.DLT_ORIGINAL_TOPIC,
                        TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)))))
        .get();
  }

  private void publicarNaDlqComMarcadorRecent(String payload) throws Exception {
    kafkaTemplate
        .send(
            new ProducerRecord<>(
                TOPICO_DLQ,
                null,
                ASSINATURA_ID,
                payload,
                List.of(
                    new RecordHeader(
                        KafkaHeaders.DLT_ORIGINAL_TOPIC,
                        TOPICO_ORIGINAL.getBytes(StandardCharsets.UTF_8)),
                    new RecordHeader(
                        ReplayDlqConsumer.HEADER_REPUBLICACAO,
                        Long.toString(System.currentTimeMillis())
                            .getBytes(StandardCharsets.UTF_8)))))
        .get();
  }

  private static void coletar(
      KafkaConsumer<String, String> observador, List<ConsumerRecord<String, String>> destino) {
    ConsumerRecords<String, String> registros = observador.poll(Duration.ofMillis(500));
    registros.forEach(destino::add);
  }

  private static long contar(List<ConsumerRecord<String, String>> registros, String payload) {
    return registros.stream().filter(registro -> registro.value().contains(payload)).count();
  }

  /**
   * Constroi um {@link KafkaConsumer} manual inscrito no topico observado, com groupId unico e
   * {@code auto.offset.reset=earliest}, para ler as mensagens republicadas pelo {@link
   * ReplayDlqConsumer}.
   */
  private KafkaConsumer<String, String> consumidor(String topico) {
    String groupId = "teste-replay-" + System.currentTimeMillis();
    Map<String, Object> props =
        new HashMap<>(KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka));
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }
}
