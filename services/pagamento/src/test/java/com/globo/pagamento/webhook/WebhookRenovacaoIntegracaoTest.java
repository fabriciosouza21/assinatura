package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de integracao ponta-a-ponta do webhook de renovacao contra o Postgres real e o broker
 * EmbeddedKafka.
 *
 * <p>Exercita {@code POST /webhooks/payments} com assinatura HMAC real, passando pelo despacho
 * adesao-vs-renovacao, pela decisao (com lock pessimista), pela publicacao no topico {@code
 * renovacao-resultado} e pela gravacao do event id somente apos o ACK do Kafka. O gateway de
 * pagamento e substituido por um mock para isolar a rede.
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
    })
class WebhookRenovacaoIntegracaoTest {

  private static final String TOPICO_RENOVACAO_RESULTADO = "renovacao-resultado";
  private static final String SECRET = "mock-webhook-secret";
  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String PAYMENT_ID = "00000000-0000-0000-0000-000000000021";
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Autowired private MockMvc mockMvc;
  @Autowired private TentativaCobrancaRepository tentativaCobrancaRepository;
  @Autowired private WebhookEventoProcessadoRepository eventoRepository;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private GatewayPagamentoClient gateway;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve aprovar a renovacao, publicar no topico e gravar o event id apos o ACK")
  void deveAprovarPublicarEgravarEventId() throws Exception {
    when(gateway.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    semearPagamentoComTentativaCobrada();

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

      assertThat(tentativaCobrancaRepository.findAll().getFirst().getStatus())
          .as("Tentativa aprovada pela decisao")
          .isEqualTo(StatusTentativa.APROVADA);
      assertThat(eventoRepository.findAll().getFirst().getEventId())
          .as("Event id gravado apos o ACK do Kafka")
          .isEqualTo(EVENT_ID);

      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> registros = consumer.poll(Duration.ofSeconds(1));
                assertThat(registros)
                    .as("Topico de renovacao-resultado recebeu o evento de aprovacao")
                    .extracting(ConsumerRecord::value)
                    .anyMatch(
                        valor ->
                            valor.contains("\"renovacaoId\":\"" + RENOVACAO_ID + "\"")
                                && valor.contains("\"paymentId\":\"" + PAYMENT_ID + "\""));
              });
    }
  }

  @Test
  @DisplayName("Deve devolver 503 sem gravar o event id quando a decisao ainda nao e possivel")
  void deveDevolver503SemGravarEventIdQuandoDecisaoIndisponivel() throws Exception {
    when(gateway.consultarStatus(PAYMENT_ID)).thenReturn(StatusGateway.APPROVED);
    semearPagamentoSemTentativaCobrada();

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

  private void semearPagamentoComTentativaCobrada() {
    semearPagamento();
    jdbcTemplate.update(
        "INSERT INTO tentativa_cobranca (renovacao_id, numero, status, payment_id)"
            + " VALUES (?, ?, ?, ?)",
        RENOVACAO_ID,
        1,
        StatusTentativa.PENDENTE.name(),
        PAYMENT_ID);
  }

  private void semearPagamentoSemTentativaCobrada() {
    semearPagamento();
  }

  private void semearPagamento() {
    jdbcTemplate.update(
        "INSERT INTO pagamento_renovacao (renovacao_id, assinatura_id, plano, valor,"
            + " ciclo_referencia) VALUES (?, ?, ?, ?, ?)",
        RENOVACAO_ID,
        ASSINATURA_ID,
        Plano.BASICO.name(),
        new BigDecimal("19.90"),
        2);
  }

  private String corpo() {
    return jsonMapper.writeValueAsString(
        new WebhookEvent(
            EVENT_ID,
            "payment.updated",
            new WebhookData(UUID.fromString(PAYMENT_ID), UUID.fromString(RENOVACAO_ID))));
  }

  private String assinar(String corpo) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256="
        + java.util.HexFormat.of().formatHex(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
  }

  private KafkaConsumer<String, String> consumidorDoTopico() {
    String groupId = "teste-webhook-renovacao-" + System.currentTimeMillis();
    Map<String, Object> props =
        new HashMap<>(KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka));
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(TOPICO_RENOVACAO_RESULTADO));
    return consumer;
  }

  @AfterEach
  void limparTabelas() {
    jdbcTemplate.update("DELETE FROM webhook_evento_processado");
    jdbcTemplate.update("DELETE FROM tentativa_cobranca");
    jdbcTemplate.update("DELETE FROM pagamento_renovacao");
  }
}
