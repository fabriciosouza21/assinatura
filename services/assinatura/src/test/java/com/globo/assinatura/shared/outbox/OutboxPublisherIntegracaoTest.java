package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do fluxo de publicacao da outbox contra um broker Kafka embarcado.
 *
 * <p>Garante que o publisher drena a outbox e roteia cada {@code eventType} ao seu topico de
 * destino ({@code AssinaturaSolicitada -> assinatura-solicitada}, {@code RenovacaoSolicitada ->
 * renovacao-solicitada}), com a key de roteamento igual ao {@code aggregateId} e o payload do
 * contrato. Exercita o publisher chamando {@code publicarPendentes()} direto, isolando o fluxo de
 * publicacao da janela de agendamento do @{@code Scheduled}. A captura da mensagem publica e feita
 * por um listener de teste (nao por um consumer manual). Usa o {@code @EmbeddedKafka} do
 * spring-kafka-test, dispensando o broker do docker-compose.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"assinatura-solicitada", "renovacao-solicitada"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@DirtiesContext
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "app.kafka.rotas-evento-topico.AssinaturaSolicitada=assinatura-solicitada",
      "app.kafka.rotas-evento-topico.RenovacaoSolicitada=renovacao-solicitada",
      "app.outbox.intervalo-ms=60000",
      "app.outbox.tamanho-lote=10",
      "app.outbox.backoff-inicial-segundos=0",
      "app.outbox.jitter-millis=0",
    })
class OutboxPublisherIntegracaoTest {

  @Autowired private OutboxRepository outboxRepository;
  @Autowired private OutboxPublisher publisher;
  @Autowired private OutboxRecuperacaoScheduler recuperacaoScheduler;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
  @Autowired private CapturadorEventoAssinaturaSolicitada capturadorAssinatura;
  @Autowired private CapturadorEventoRenovacaoSolicitada capturadorRenovacao;

  @BeforeEach
  void aguardarInicializacaoDosListeners() {
    aguardarAtribuicao("capturador-assinatura-solicitada");
    aguardarAtribuicao("capturador-renovacao-solicitada");
    capturadorAssinatura.limpar();
    capturadorRenovacao.limpar();
  }

  private void aguardarAtribuicao(String idListener) {
    MessageListenerContainer container = listenerRegistry.getListenerContainer(idListener);
    assertThat(container).as("Container do listener %s registrado", idListener).isNotNull();
    ContainerTestUtils.waitForAssignment(container, 1);
  }

  @ParameterizedTest(name = "[{index}] eventType={0} -> topico {1}")
  @CsvSource({
    "AssinaturaSolicitada, assinatura-solicitada",
    "RenovacaoSolicitada, renovacao-solicitada"
  })
  @DisplayName("Deve rotear o evento pendente para o topico correspondente ao eventType")
  void deveRoterEventoPendenteParaTopicoCorrespondente(String eventType, String topico) {
    outboxRepository.saveAndFlush(eventoPendente(eventType, topico));
    publisher.publicarPendentes();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              ConsumerRecord<String, String> registro = capturadorDo(topico).ultimoRegistro();
              assertThat(registro)
                  .as("Evento %s publicado e capturado no topico %s", eventType, topico)
                  .isNotNull();
              assertThat(registro.key())
                  .as("Key de roteamento igual ao aggregateId")
                  .isEqualTo("22222222-2222-2222-2222-222222222222");
              assertThat(registro.value())
                  .as("Payload publicado com o campo do contrato")
                  .contains("assinaturaId")
                  .contains("22222222-2222-2222-2222-222222222222");
            });
    assertThat(capturadorDoOutro(topico).ultimoRegistro())
        .as("Evento %s nao vaza para o outro topico", eventType)
        .isNull();
  }

  private CapturadorEvento capturadorDo(String topico) {
    return "renovacao-solicitada".equals(topico) ? capturadorRenovacao : capturadorAssinatura;
  }

  @Test
  @DisplayName("Deve recuperar evento da DLQ e publica-lo no topico correspondente")
  void deveRecuperarEventoDaDlqPublicarNoTopico() {
    OutboxEvent evento = eventoEmFalhaRecuperavel();
    outboxRepository.saveAndFlush(evento);

    recuperacaoScheduler.recuperarFalhas();

    OutboxEvent recuperado = outboxRepository.findById(evento.getEventId()).orElseThrow();
    assertThat(recuperado.getStatus())
        .as("Evento em falha e promovido para RETENTATIVA_DLQ")
        .isEqualTo(OutboxStatus.RETENTATIVA_DLQ);

    publisher.publicarPendentes();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              ConsumerRecord<String, String> registro = capturadorAssinatura.ultimoRegistro();
              assertThat(registro)
                  .as("Evento recuperado publicado no topico de assinatura")
                  .isNotNull();
              assertThat(registro.key())
                  .as("Key de roteamento igual ao aggregateId")
                  .isEqualTo("33333333-3333-3333-3333-333333333333");
              assertThat(registro.value())
                  .as("Payload do evento recuperado")
                  .contains("33333333-3333-3333-3333-333333333333");
            });

    OutboxEvent publicado = outboxRepository.findById(evento.getEventId()).orElseThrow();
    assertThat(publicado.getStatus())
        .as("Evento recuperado transita para PUBLICADO")
        .isEqualTo(OutboxStatus.PUBLICADO);
  }

  private static OutboxEvent eventoEmFalhaRecuperavel() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.randomUUID(),
            "Assinatura",
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            "AssinaturaSolicitada",
            "{\"eventId\":\"x\",\"assinaturaId\":\"33333333-3333-3333-3333-333333333333\"}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    return evento;
  }

  private CapturadorEvento capturadorDoOutro(String topico) {
    return "renovacao-solicitada".equals(topico) ? capturadorAssinatura : capturadorRenovacao;
  }

  private static OutboxEvent eventoPendente(String eventType, String topico) {
    return OutboxEvent.criar(
        UUID.randomUUID(),
        "Assinatura",
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        eventType,
        "{\"eventId\":\"x\",\"assinaturaId\":\"22222222-2222-2222-2222-222222222222\"}");
  }

  /** Registra os beans dos listeners de captura no contexto de teste. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ConfiguracaoKafkaTeste {

    @Bean
    CapturadorEventoAssinaturaSolicitada capturadorEventoAssinaturaSolicitada() {
      return new CapturadorEventoAssinaturaSolicitada();
    }

    @Bean
    CapturadorEventoRenovacaoSolicitada capturadorEventoRenovacaoSolicitada() {
      return new CapturadorEventoRenovacaoSolicitada();
    }
  }

  /** Listener de teste que captura o ultimo registro publicado num topico. */
  interface CapturadorEvento {

    ConsumerRecord<String, String> ultimoRegistro();

    void limpar();
  }

  static class CapturadorEventoAssinaturaSolicitada implements CapturadorEvento {

    private final AtomicReference<ConsumerRecord<String, String>> capturado =
        new AtomicReference<>();

    @KafkaListener(
        id = "capturador-assinatura-solicitada",
        topics = "assinatura-solicitada",
        groupId = "teste-publisher-outbox",
        autoStartup = "true")
    void capturar(ConsumerRecord<String, String> registro) {
      capturado.set(registro);
    }

    @Override
    public ConsumerRecord<String, String> ultimoRegistro() {
      return capturado.get();
    }

    @Override
    public void limpar() {
      capturado.set(null);
    }
  }

  static class CapturadorEventoRenovacaoSolicitada implements CapturadorEvento {

    private final AtomicReference<ConsumerRecord<String, String>> capturado =
        new AtomicReference<>();

    @KafkaListener(
        id = "capturador-renovacao-solicitada",
        topics = "renovacao-solicitada",
        groupId = "teste-publisher-outbox",
        autoStartup = "true")
    void capturar(ConsumerRecord<String, String> registro) {
      capturado.set(registro);
    }

    @Override
    public ConsumerRecord<String, String> ultimoRegistro() {
      return capturado.get();
    }

    @Override
    public void limpar() {
      capturado.set(null);
    }
  }
}
