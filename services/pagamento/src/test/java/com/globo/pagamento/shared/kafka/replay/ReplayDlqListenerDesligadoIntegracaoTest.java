package com.globo.pagamento.shared.kafka.replay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do listener de replay desligado por padrao.
 *
 * <p>Sem {@code app.kafka.replay.auto-startup=true}, o container do replay e registrado no {@link
 * KafkaListenerEndpointRegistry} com {@code autoStartup=false} e nunca inicia, de modo que nenhum
 * consumer se conecta aos topicos {@code *-dlq} e o comportamento e identico ao de antes do replay.
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
    })
class ReplayDlqListenerDesligadoIntegracaoTest {

  @Autowired private KafkaListenerEndpointRegistry registry;

  @Test
  @DisplayName("Nao deve iniciar o container do replay quando desligado por padrao")
  void naoIniciaContainerDoReplayQuandoDesligadoPorPadrao() {
    MessageListenerContainer container =
        registry.getAllListenerContainers().stream()
            .filter(c -> "pagamento-replay-dlq".equals(c.getGroupId()))
            .findFirst()
            .orElseThrow();

    assertThat(container.isAutoStartup()).as("autoStartup false por padrao").isFalse();
    assertThat(container.isRunning())
        .as("Container do replay nao inicia sem habilitacao")
        .isFalse();
  }
}
