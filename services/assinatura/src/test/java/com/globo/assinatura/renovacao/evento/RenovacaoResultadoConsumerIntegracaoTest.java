package com.globo.assinatura.renovacao.evento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.renovacao.ProcessarRenovacaoResultado;
import com.globo.assinatura.renovacao.Renovacao;
import com.globo.assinatura.renovacao.RenovacaoRepository;
import com.globo.assinatura.renovacao.StatusRenovacao;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * Teste de integracao ponta-a-ponta do {@link RenovacaoResultadoConsumer} contra o broker
 * EmbeddedKafka e o Postgres real.
 *
 * <p>Publica um evento sintetico no topico {@code renovacao-resultado} e verifica, via Awaitility,
 * que o consumer do contexto Spring consome a mensagem, delega ao command {@link
 * ProcessarRenovacaoResultado} e transita a assinatura e a renovacao correlacionadas no banco de
 * dados, alem de gravar o evento de dominio correspondente ({@code AssinaturaRenovada} para
 * aprovacao ou {@code AssinaturaSuspensa} para tentativas esgotadas) na outbox. Um teste adicional
 * publica um payload invalido e verifica o envio para o topico {@code renovacao-resultado-dlq}.
 *
 * <p>O teste apenas publica no topico via {@link KafkaTemplate}; o {@code @KafkaListener} do app e
 * disparado automaticamente pelo contexto Spring inicializado pelo EmbeddedKafka.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"renovacao-resultado", "renovacao-resultado-dlq"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=${INTEGRATION_DB_URL:jdbc:postgresql://localhost:5433/assinatura}",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura"
    })
class RenovacaoResultadoConsumerIntegracaoTest {

  private static final String TOPICO_RENOVACAO_RESULTADO = "renovacao-resultado";

  private static final String TOPICO_RENOVACAO_RESULTADO_DLQ = "renovacao-resultado-dlq";

  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private RenovacaoRepository renovacaoRepository;
  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EmbeddedKafkaBroker embeddedKafka;

  @Test
  @DisplayName("Deve renovar a assinatura e gravar AssinaturaRenovada ao consumir evento APROVADO")
  void deveRenovarAssinaturaAoConsumirEventoAprovado() throws Exception {
    CenarioRenovacao cenario = persistirAssinaturaEmRenovacao();
    Assinatura assinatura = cenario.assinatura();
    Renovacao renovacao = cenario.renovacao();
    UUID renovacaoUuid = UUID.fromString(renovacao.getUuid());

    Map<String, Object> envelope = new HashMap<>();
    envelope.put("tipo", "APROVADO");
    envelope.put("eventId", UUID.randomUUID());
    envelope.put("ocorridoEm", Instant.parse("2026-08-01T12:00:00Z"));
    envelope.put("renovacaoId", renovacaoUuid);
    envelope.put("assinaturaId", UUID.fromString(assinatura.getUuid()));
    envelope.put("paymentId", "pay_" + UUID.randomUUID());
    envelope.put("cicloReferencia", 1);
    String payload = objectMapper.writeValueAsString(envelope);

    kafkaTemplate.send(TOPICO_RENOVACAO_RESULTADO, renovacao.getUuid(), payload);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Assinatura assinaturaAtualizada =
                  assinaturaRepository.findByUuid(assinatura.getUuid()).orElseThrow();
              assertThat(assinaturaAtualizada.getStatus())
                  .as("Status transitou para ATIVA apos consumo do evento APROVADO")
                  .isEqualTo(StatusAssinatura.ATIVA);
              assertThat(assinaturaAtualizada.getFimCiclo())
                  .as("Fim do ciclo avancado em uma duracao de ciclo")
                  .isEqualTo(assinatura.getFimCiclo().plusDays(30));

              Renovacao renovacaoAtualizada =
                  renovacaoRepository.findById(renovacao.getId()).orElseThrow();
              assertThat(renovacaoAtualizada.getStatus())
                  .as("Renovacao aprovada")
                  .isEqualTo(StatusRenovacao.APROVADA);

              List<OutboxEvent> eventos = outboxRepository.findAll();
              assertThat(eventos)
                  .as("Evento AssinaturaRenovada gravado na outbox para a renovacao")
                  .anySatisfy(
                      e -> {
                        assertThat(e.getEventType()).isEqualTo("AssinaturaRenovada");
                        assertThat(e.getAggregateId()).isEqualTo(renovacaoUuid);
                      });
            });
  }

  @Test
  @DisplayName(
      "Deve suspender a assinatura e gravar AssinaturaSuspensa ao consumir evento ESGOTADO")
  void deveSuspenderAssinaturaAoConsumirEventoEsgotado() throws Exception {
    CenarioRenovacao cenario = persistirAssinaturaEmRenovacao();
    Assinatura assinatura = cenario.assinatura();
    Renovacao renovacao = cenario.renovacao();
    UUID renovacaoUuid = UUID.fromString(renovacao.getUuid());

    Map<String, Object> envelope = new HashMap<>();
    envelope.put("tipo", "ESGOTADO");
    envelope.put("eventId", UUID.randomUUID());
    envelope.put("ocorridoEm", Instant.parse("2026-08-01T12:00:00Z"));
    envelope.put("renovacaoId", renovacaoUuid);
    envelope.put("assinaturaId", UUID.fromString(assinatura.getUuid()));
    envelope.put("cicloReferencia", 1);
    String payload = objectMapper.writeValueAsString(envelope);

    kafkaTemplate.send(TOPICO_RENOVACAO_RESULTADO, renovacao.getUuid(), payload);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Assinatura assinaturaAtualizada =
                  assinaturaRepository.findByUuid(assinatura.getUuid()).orElseThrow();
              assertThat(assinaturaAtualizada.getStatus())
                  .as("Status transitou para SUSPENSA apos consumo do evento ESGOTADO")
                  .isEqualTo(StatusAssinatura.SUSPENSA);

              Renovacao renovacaoAtualizada =
                  renovacaoRepository.findById(renovacao.getId()).orElseThrow();
              assertThat(renovacaoAtualizada.getStatus())
                  .as("Renovacao com tentativas esgotadas")
                  .isEqualTo(StatusRenovacao.TENTATIVAS_ESGOTADA);

              List<OutboxEvent> eventos = outboxRepository.findAll();
              assertThat(eventos)
                  .as("Evento AssinaturaSuspensa gravado na outbox para a renovacao")
                  .anySatisfy(
                      e -> {
                        assertThat(e.getEventType()).isEqualTo("AssinaturaSuspensa");
                        assertThat(e.getAggregateId()).isEqualTo(renovacaoUuid);
                      });
            });
  }

  @Test
  @DisplayName("Deve enviar payload invalido para o topico de falha")
  void deveEnviarPayloadInvalidoParaTopicoDeFalha() throws Exception {
    String renovacaoId = "44444444-4444-4444-4444-444444444444";
    String payloadInvalido = "{isto-nao-e-json";

    try (KafkaConsumer<String, String> consumer = consumidorDlq()) {
      consumer.subscribe(List.of(TOPICO_RENOVACAO_RESULTADO_DLQ));
      consumer.poll(Duration.ofSeconds(1));

      kafkaTemplate.send(TOPICO_RENOVACAO_RESULTADO, renovacaoId, payloadInvalido).get();

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
   * Persiste uma assinatura ativa, transita para {@link StatusAssinatura#EM_RENOVACAO} e cria a
   * renovacao {@link StatusRenovacao#PENDENTE} associada, prontas para o consumer processar o
   * resultado.
   *
   * <p>Email unico por execucao para nao conflitar com os demais testes de integracao que
   * compartilham a base sem cleanup.
   */
  private CenarioRenovacao persistirAssinaturaEmRenovacao() {
    Usuario usuario =
        usuarioRepository.save(
            new Usuario("Fulano", "renov-resultado-" + UUID.randomUUID() + "@example.com"));
    Assinatura assinatura = new Assinatura(usuario.getId(), Plano.PREMIUM);
    LocalDate inicio = LocalDate.now().minusDays(60);
    LocalDate vencimento = LocalDate.now().minusDays(30);
    assinatura.ativar(inicio, vencimento);
    assinaturaRepository.saveAndFlush(assinatura);
    assinatura.iniciarRenovacao();
    assinaturaRepository.saveAndFlush(assinatura);
    Renovacao renovacao =
        renovacaoRepository.saveAndFlush(
            new Renovacao(assinatura.getId(), assinatura.getFimCiclo(), 1));
    return new CenarioRenovacao(assinatura, renovacao);
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

  private record CenarioRenovacao(Assinatura assinatura, Renovacao renovacao) {}
}
