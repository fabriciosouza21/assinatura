package com.globo.assinatura.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * <p>Garante que o publisher drena a outbox e publica {@code AssinaturaSolicitada} no topico {@code
 * assinatura-solicitada} com a key de roteamento e o payload do contrato. Exercita o publisher
 * chamando {@code publicarPendentes()} direto, isolando o fluxo de publicacao da janela de
 * agendamento do @{@code Scheduled}. A captura da mensagem publica e feita por um listener de teste
 * (nao por um consumer manual). Usa o {@code @EmbeddedKafka} do spring-kafka-test, dispensando o
 * broker do docker-compose.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"assinatura-solicitada"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@DirtiesContext
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "spring.kafka.consumer.auto-offset-reset=earliest",
      "app.kafka.topico-assinatura-solicitada=assinatura-solicitada",
      "app.outbox.intervalo-ms=60000",
      "app.outbox.tamanho-lote=10",
    })
class OutboxPublisherIntegracaoTest {

  @Autowired private OutboxRepository outboxRepository;
  @Autowired private OutboxPublisher publisher;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
  @Autowired private CapturadorEventoAssinaturaSolicitada capturador;

  @BeforeEach
  void aguardarInicializacaoDoListener() {
    MessageListenerContainer container =
        listenerRegistry.getListenerContainer("capturador-assinatura-solicitada");
    assertThat(container).as("Container do listener de teste registrado").isNotNull();
    ContainerTestUtils.waitForAssignment(container, 1);
    capturador.limpar();
  }

  @Test
  @DisplayName("Deve publicar evento pendente no topico assinatura-solicitada")
  void devePublicarEventoPendenteNoTopico() {
    outboxRepository.saveAndFlush(eventoPendente());
    publisher.publicarPendentes();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              ConsumerRecord<String, String> registro = capturador.ultimoRegistro();
              assertThat(registro).as("Evento publicado e capturado pelo listener").isNotNull();
              assertThat(registro.key())
                  .as("Key de roteamento igual ao aggregateId")
                  .isEqualTo("22222222-2222-2222-2222-222222222222");
              assertThat(registro.value())
                  .as("Payload publicado com os campos do contrato")
                  .contains("assinaturaId")
                  .contains("22222222-2222-2222-2222-222222222222");
            });
  }

  private OutboxEvent eventoPendente() {
    return OutboxEvent.criar(
        UUID.fromString("55555555-5555-5555-5555-555555555555"),
        "Assinatura",
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        "AssinaturaSolicitada",
        "{\"eventId\":\"x\",\"assinaturaId\":\"22222222-2222-2222-2222-222222222222\"}");
  }

  /** Registra o bean do listener de captura no contexto de teste. */
  @TestConfiguration(proxyBeanMethods = false)
  static class ConfiguracaoKafkaTeste {

    @Bean
    CapturadorEventoAssinaturaSolicitada capturadorEventoAssinaturaSolicitada() {
      return new CapturadorEventoAssinaturaSolicitada();
    }
  }

  /** Listener de teste que captura o ultimo registro publicado no topico. */
  static class CapturadorEventoAssinaturaSolicitada {

    private final AtomicReference<ConsumerRecord<String, String>> capturado =
        new AtomicReference<>();

    @KafkaListener(
        id = "capturador-assinatura-solicitada",
        topics = "${app.kafka.topico-assinatura-solicitada}",
        groupId = "teste-publisher-outbox",
        autoStartup = "true")
    void capturar(ConsumerRecord<String, String> registro) {
      capturado.set(registro);
    }

    ConsumerRecord<String, String> ultimoRegistro() {
      return capturado.get();
    }

    void limpar() {
      capturado.set(null);
    }
  }
}
