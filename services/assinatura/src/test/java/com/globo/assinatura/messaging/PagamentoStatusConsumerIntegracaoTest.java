package com.globo.assinatura.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * Teste de integracao ponta-a-ponta do {@link PagamentoStatusConsumer} contra o broker
 * EmbeddedKafka e o Postgres real.
 *
 * <p>Publica um evento sintetico {@code APPROVED} no topico {@code pagamento-status-atualizado} e
 * verifica, via Awaitility, que o consumer do contexto Spring consome a mensagem, delega ao command
 * {@link com.globo.assinatura.assinatura.ProcessarPagamento} e transita a assinatura correlacionada
 * para {@link StatusAssinatura#ATIVA} no banco de dados, com datas de vigencia preenchidas.
 *
 * <p>O teste apenas publica no topico via {@link KafkaTemplate}; o {@code @KafkaListener} do app e
 * disparado automaticamente pelo contexto Spring inicializado pelo EmbeddedKafka.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"pagamento-status-atualizado", "pagamento-status-atualizado-dlq"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=${INTEGRATION_DB_URL:jdbc:postgresql://localhost:5433/assinatura}",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class PagamentoStatusConsumerIntegracaoTest {

  private static final String TOPICO_PAGAMENTO_STATUS = "pagamento-status-atualizado";

  private static final String TOPICO_PAGAMENTO_STATUS_DLQ = "pagamento-status-atualizado-dlq";

  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;

  @Test
  @DisplayName("Deve ativar a assinatura no banco ao consumir evento APPROVED do topico Kafka")
  void deveAtivarAssinaturaAoConsumirEventoAprovado() throws Exception {
    Usuario usuario = usuarioRepository.save(new Usuario("Fulano", "fulano.aprovado@example.com"));
    Assinatura assinatura =
        assinaturaRepository.saveAndFlush(new Assinatura(usuario.getId(), Plano.BASICO));

    UUID assinaturaId = UUID.fromString(assinatura.getUuid());
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            Instant.parse("2026-07-30T12:00:00Z"),
            assinaturaId,
            StatusPagamento.APPROVED,
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    String payload = objectMapper.writeValueAsString(evento);

    kafkaTemplate.send(TOPICO_PAGAMENTO_STATUS, assinaturaId.toString(), payload);

    await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Assinatura atualizada =
                  assinaturaRepository.findByUuid(assinatura.getUuid()).orElseThrow();
              assertThat(atualizada.getStatus())
                  .as("Status transitou para ATIVA apos consumo do evento APPROVED")
                  .isEqualTo(StatusAssinatura.ATIVA);
              assertThat(atualizada.getDataInicio())
                  .as("Data de inicio da vigencia preenchida")
                  .isNotNull();
              assertThat(atualizada.getDataExpiracao())
                  .as("Data de expiracao da vigencia preenchida")
                  .isNotNull();
            });
  }

  @Test
  @DisplayName("Deve enviar payload invalido para o topico de falha")
  void deveEnviarPayloadInvalidoParaTopicoDeFalha() throws Exception {
    String assinaturaId = "33333333-3333-3333-3333-333333333333";
    String payloadInvalido = "{isto-nao-e-json";

    try (KafkaConsumer<String, String> consumer = consumidorDlq()) {
      consumer.subscribe(List.of(TOPICO_PAGAMENTO_STATUS_DLQ));
      consumer.poll(Duration.ofSeconds(1));

      kafkaTemplate.send(TOPICO_PAGAMENTO_STATUS, assinaturaId, payloadInvalido).get();

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> registros = consumer.poll(Duration.ofSeconds(1));
                assertThat(registros)
                    .as("DLQ recebeu o payload invalido")
                    .extracting(ConsumerRecord::value)
                    .anyMatch(valor -> valor.contains("isto-nao-e-json"));
              });
    }
  }

  /**
   * Constroi um {@link KafkaConsumer} manual inscrito no topico DLQ, com groupId unico e {@code
   * auto.offset.reset=earliest}, para observar os registros publicados pelo {@code
   * DeadLetterPublishingRecoverer} do {@link MessagingConfig}.
   */
  private KafkaConsumer<String, String> consumidorDlq() {
    String groupId = "teste-dlq-" + System.currentTimeMillis();
    Map<String, Object> props =
        new HashMap<>(KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka));
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class.getName());
    props.put("value.deserializer", StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }
}
