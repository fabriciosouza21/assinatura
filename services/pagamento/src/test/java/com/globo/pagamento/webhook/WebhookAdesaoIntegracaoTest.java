package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.pagamento.cobranca.StatusCobranca;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de integracao ponta-a-ponta do webhook de adesao contra o Postgres real e o broker
 * EmbeddedKafka.
 *
 * <p>Exercita {@code POST /webhooks/payments} com assinatura HMAC real no ramo de adesao:
 * atualizacao da cobranca, gravacao do evento na outbox na mesma transacao e publicacao no topico
 * {@code pagamento-status-atualizado} via {@link com.globo.pagamento.outbox.OutboxPublisher}. O
 * gateway de pagamento e substituido por um mock para isolar a rede.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
    partitions = 1,
    topics = {
      "renovacao-resultado",
      "assinatura-solicitada",
      "renovacao-solicitada",
      "pagamento-status-atualizado"
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "app.gateway.webhook-secret=mock-webhook-secret",
      "app.renovacao.scheduler-intervalo-ms=3600000",
      "app.renovacao.scheduler-delay-inicial-ms=3600000",
      "app.outbox.intervalo-ms=100",
    })
class WebhookAdesaoIntegracaoTest {

  private static final String TOPICO_PAGAMENTO_STATUS = "pagamento-status-atualizado";
  private static final String SECRET = "mock-webhook-secret";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000021";
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Autowired private MockMvc mockMvc;
  @Autowired private WebhookEventoProcessadoRepository eventoRepository;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private GatewayPagamentoClient gateway;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve aprovar a cobranca da adesao, publicar no topico e gravar o dedup")
  void deveAprovarPublicarEgravarDedup() throws Exception {
    when(gateway.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    semearCobrancaPendente();

    try (KafkaConsumer<String, String> consumer = consumidorDoTopico()) {
      consumer.poll(Duration.ofSeconds(1));

      mockMvc
          .perform(
              post("/webhooks/payments")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("X-Mock-Event-Id", EVENT_ID.toString())
                  .header("X-Mock-Signature", assinar(corpo()))
                  .content(corpo()))
          .andExpect(status().isOk());

      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT status FROM cobranca WHERE payment_id = ?", String.class, PAYMENT_ID))
          .as("Cobranca da adesao aprovada pela decisao")
          .isEqualTo(StatusCobranca.APPROVED.name());
      assertThat(eventoRepository.findAll().getFirst().getEventId())
          .as("Event id gravado na mesma transacao da decisao")
          .isEqualTo(EVENT_ID);

      String valorEvento = aguardarEvento(consumer, "assinaturaId", ASSINATURA_ID);
      JsonNode no = jsonMapper.readTree(valorEvento);
      assertThat(no.get("assinaturaId").asText())
          .as("Topico de pagamento-status-atualizado recebeu o evento da adesao")
          .isEqualTo(ASSINATURA_ID);
      assertThat(no.get("paymentId").asText())
          .as("Pagamento da adesao no evento publicado")
          .isEqualTo(PAYMENT_ID);
    }
  }

  @Test
  @DisplayName("Deve devolver 503 quando a consulta ao gateway falha")
  void deveDevolver503QuandoGatewayFalha() throws Exception {
    when(gateway.consultarStatus(PAYMENT_ID)).thenThrow(new RuntimeException("gateway fora do ar"));

    mockMvc
        .perform(
            post("/webhooks/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Mock-Event-Id", EVENT_ID.toString())
                .header("X-Mock-Signature", assinar(corpo()))
                .content(corpo()))
        .andExpect(status().isServiceUnavailable());

    assertThat(eventoRepository.findAll())
        .as("Sem dedup o reenvio do gateway volta a processar")
        .isEmpty();
  }

  private void semearCobrancaPendente() {
    jdbcTemplate.update(
        "INSERT INTO cobranca (assinatura_uuid, payment_id, status) VALUES (?, ?, ?)",
        ASSINATURA_ID,
        PAYMENT_ID,
        StatusCobranca.PENDING.name());
  }

  private String corpo() {
    return jsonMapper.writeValueAsString(
        new WebhookEvent(
            EVENT_ID,
            "payment.updated",
            new WebhookData(UUID.fromString(PAYMENT_ID), UUID.fromString(ASSINATURA_ID))));
  }

  private String assinar(String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256="
        + java.util.HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
  }

  private String aguardarEvento(
      KafkaConsumer<String, String> consumer, String campo, String valorEsperado) {
    long limite = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
    while (System.currentTimeMillis() < limite) {
      for (ConsumerRecord<String, String> registro : consumer.poll(Duration.ofMillis(500))) {
        JsonNode no = jsonMapper.readTree(registro.value());
        if (no.has(campo) && valorEsperado.equals(no.get(campo).asText())) {
          return registro.value();
        }
      }
    }
    return null;
  }

  private KafkaConsumer<String, String> consumidorDoTopico() {
    String groupId = "teste-webhook-adesao-" + System.currentTimeMillis();
    Map<String, Object> props =
        new HashMap<>(KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka));
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(TOPICO_PAGAMENTO_STATUS));
    return consumer;
  }

  @AfterEach
  void limparTabelas() {
    jdbcTemplate.update("DELETE FROM webhook_evento_processado");
    jdbcTemplate.update("DELETE FROM outbox");
    jdbcTemplate.update("DELETE FROM cobranca");
  }
}
