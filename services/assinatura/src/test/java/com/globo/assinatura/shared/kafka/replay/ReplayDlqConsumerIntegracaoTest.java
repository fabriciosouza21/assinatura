package com.globo.assinatura.shared.kafka.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.shared.contrato.PagamentoStatusAtualizado;
import com.globo.assinatura.shared.contrato.StatusPagamento;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
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
 * publica um evento valido no topico DLQ e verifica que ele e republicado no topico original com a
 * mesma chave e payload e processado pelo consumer (assinatura transita para {@link
 * StatusAssinatura#ATIVA}); (2) publica um registro com marcador de republicacao recente e verifica
 * que a republicacao so ocorre apos o intervalo configurado, sem ciclo rapido.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {
      "pagamento-status-atualizado",
      "pagamento-status-atualizado-dlq",
      "renovacao-resultado-dlq"
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=${INTEGRATION_DB_URL:jdbc:postgresql://localhost:5433/assinatura}",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "app.kafka.replay.auto-startup=true",
      "app.kafka.replay.backoff-interval-ms=5000",
    })
class ReplayDlqConsumerIntegracaoTest {

  private static final String TOPICO_ORIGINAL = "pagamento-status-atualizado";
  private static final String TOPICO_DLQ = "pagamento-status-atualizado-dlq";
  private static final String CHAVE = "550e8400-e29b-41d4-a716-446655440000";

  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;

  @Test
  @DisplayName("Deve republicar a mensagem da DLQ no topico original com a mesma chave e payload")
  void deveRepublicarDlqNoTopicoOriginal() throws Exception {
    Usuario usuario = usuarioRepository.save(new Usuario("Fulano", "fulano.replay@example.com"));
    Assinatura assinatura =
        assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.BASICO));
    UUID assinaturaId = UUID.fromString(assinatura.getUuid());
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("77777777-7777-7777-7777-777777777777"),
            Instant.parse("2026-07-30T12:00:00Z"),
            assinaturaId,
            StatusPagamento.APPROVED,
            UUID.fromString("88888888-8888-8888-8888-888888888888"));
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
                    .anyMatch(
                        registro ->
                            registro.value().contains("77777777-7777-7777-7777-777777777777"));
              });
      ConsumerRecord<String, String> republicada =
          republicadas.stream()
              .filter(registro -> registro.value().contains("77777777-7777-7777-7777-777777777777"))
              .findFirst()
              .orElseThrow();
      assertThat(republicada.key()).as("Chave preservada no replay").isEqualTo(CHAVE);
      assertThat(republicada.headers().lastHeader(ReplayDlqConsumer.HEADER_REPUBLICACAO))
          .as("Marcador de republicacao anexado")
          .isNotNull();

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                Assinatura atualizada =
                    assinaturaRepository.findByUuid(assinatura.getUuid()).orElseThrow();
                assertThat(atualizada.getStatus())
                    .as("Consumer processou a mensagem republicada")
                    .isEqualTo(StatusAssinatura.ATIVA);
              });
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
                CHAVE,
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
                CHAVE,
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
