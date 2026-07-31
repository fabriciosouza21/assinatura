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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
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
 * <p>Diferente da Track A (que cria um consumer para ler o outbox), aqui apenas publica-se no
 * topico via {@link KafkaTemplate}; o {@code @KafkaListener} do app e disparado automaticamente
 * pelo contexto Spring inicializado pelo EmbeddedKafka.
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

  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private UsuarioRepository usuarioRepository;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ObjectMapper objectMapper;

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
}
