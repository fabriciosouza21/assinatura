package com.globo.assinatura.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do fluxo de publicacao da outbox contra um broker Kafka embarcado.
 *
 * <p>Garante que o publisher drena a outbox e publica {@code AssinaturaSolicitada} no topico {@code
 * assinatura-solicitada} com a key de roteamento correta. Usa o {@code @EmbeddedKafka} do
 * spring-kafka-test, dispensando o broker do docker-compose.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"assinatura-solicitada"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "app.outbox.intervalo-ms=200",
      "app.outbox.tamanho-lote=10",
    })
class OutboxPublisherIntegracaoTest {

  @Autowired private OutboxRepository outboxRepository;

  @Autowired private UsuarioRepository usuarioRepository;

  @Autowired private AssinaturaRepository assinaturaRepository;

  @Autowired private org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafka;

  @Test
  @DisplayName("Deve publicar evento pendente no topico assinatura-solicitada")
  void devePublicarEventoPendenteNoTopico() throws Exception {
    outboxRepository.saveAndFlush(eventoPendente());

    try (KafkaConsumer<String, String> consumer = consumidor()) {
      consumer.subscribe(java.util.List.of("assinatura-solicitada"));
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () -> {
                var registros = consumer.poll(Duration.ofMillis(200));
                assertThat(registros.iterator())
                    .as("Evento publicado no topico")
                    .toIterable()
                    .extracting(ConsumerRecord::key)
                    .contains("22222222-2222-2222-2222-222222222222");
                assertThat(registros.iterator())
                    .as("Payload publicado com os campos do contrato")
                    .toIterable()
                    .extracting(ConsumerRecord::value)
                    .anyMatch(
                        v ->
                            ((String) v)
                                .contains(
                                    "\"assinaturaId\":\"22222222-2222-2222-2222-222222222222\""));
              });
    }
  }

  private OutboxEvent eventoPendente() {
    return OutboxEvent.criar(
        java.util.UUID.fromString("55555555-5555-5555-5555-555555555555"),
        "Assinatura",
        java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"),
        "AssinaturaSolicitada",
        "{\"eventId\":\"x\",\"assinaturaId\":\"22222222-2222-2222-2222-222222222222\"}");
  }

  private KafkaConsumer<String, String> consumidor() {
    java.util.Map<String, Object> props =
        new java.util.HashMap<>(
            org.springframework.kafka.test.utils.KafkaTestUtils.consumerProps(
                "teste-outbox", "true", embeddedKafka));
    props.put("auto.offset.reset", "earliest");
    props.put(
        "key.deserializer",
        org.apache.kafka.common.serialization.StringDeserializer.class.getName());
    props.put(
        "value.deserializer",
        org.apache.kafka.common.serialization.StringDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }
}
