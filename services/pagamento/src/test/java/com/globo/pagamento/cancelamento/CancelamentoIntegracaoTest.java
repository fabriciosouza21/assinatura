package com.globo.pagamento.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.shared.contrato.AssinaturaCancelada;
import com.globo.pagamento.shared.contrato.CancelamentoAgendado;
import com.globo.pagamento.shared.contrato.StatusAssinatura;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de integracao ponta-a-ponta do fluxo de cancelamento contra o Postgres real e o broker
 * EmbeddedKafka.
 *
 * <p>Publica {@code CancelamentoAgendado} e {@code AssinaturaCancelada} nos topicos consumidos pelo
 * {@link com.globo.pagamento.cancelamento.evento.CancelamentoConsumer} e verifica os efeitos no
 * banco: o cancelamento agendado nao pode tocar as tentativas em curso; o cancelamento efetivado
 * cancela as pendentes e reserva o event id; o redelivery do mesmo evento e ignorado. O gateway de
 * pagamento nao participa do fluxo; o scheduler de renovacao e adiado para nao disputar as
 * tentativas semeadas.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {
      "cancelamento-agendado",
      "assinatura-cancelada",
      "assinatura-solicitada",
      "renovacao-solicitada"
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "app.renovacao.scheduler-intervalo-ms=3600000",
      "app.renovacao.scheduler-delay-inicial-ms=3600000",
    })
class CancelamentoIntegracaoTest {

  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Test
  @DisplayName("Deve manter as tentativas pendentes ao receber cancelamento agendado")
  void deveManterTentativasAoReceberCancelamentoAgendado() {
    semearRenovacaoComTentativaPendente();

    publicar("cancelamento-agendado", payloadCancelamentoAgendado());
    aguardarConsumoDoEvento();

    assertThat(statusDaTentativa())
        .as("Tentativa intacta apos o cancelamento agendado")
        .isEqualTo(StatusTentativa.PENDENTE.name());
    assertThat(contarEventosProcessados())
        .as("Cancelamento agendado nao reserva event id")
        .isZero();
  }

  @Test
  @DisplayName("Deve cancelar as tentativas pendentes ao receber assinatura cancelada")
  void deveCancelarTentativasAoReceberAssinaturaCancelada() {
    semearRenovacaoComTentativaPendente();

    publicar("assinatura-cancelada", payloadAssinaturaCancelada());

    assertThat(aguardarStatusDaTentativa(StatusTentativa.CANCELADA.name()))
        .as("Tentativa pendente cancelada pelo evento")
        .isEqualTo(StatusTentativa.CANCELADA.name());
    assertThat(contarEventosProcessados())
        .as("Event id reservado apos o processamento")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Deve ignorar o redelivery do mesmo evento de cancelamento")
  void deveIgnorarRedeliveryDoMesmoEvento() {
    semearRenovacaoComTentativaPendente();

    publicar("assinatura-cancelada", payloadAssinaturaCancelada());
    assertThat(aguardarStatusDaTentativa(StatusTentativa.CANCELADA.name()))
        .as("Primeira entrega cancela a tentativa")
        .isEqualTo(StatusTentativa.CANCELADA.name());

    publicar("assinatura-cancelada", payloadAssinaturaCancelada());
    aguardarConsumoDoEvento();

    assertThat(contarEventosProcessados())
        .as("Redelivery nao duplica o processamento")
        .isEqualTo(1);
    assertThat(statusDaTentativa())
        .as("Status da tentativa preservado no redelivery")
        .isEqualTo(StatusTentativa.CANCELADA.name());
  }

  private void semearRenovacaoComTentativaPendente() {
    jdbcTemplate.update(
        "INSERT INTO pagamento_renovacao (renovacao_id, assinatura_id, plano, valor,"
            + " ciclo_referencia) VALUES (?, ?, ?, ?, ?)",
        RENOVACAO_ID,
        ASSINATURA_ID,
        Plano.BASICO.name(),
        new BigDecimal("19.90"),
        2);
    jdbcTemplate.update(
        "INSERT INTO tentativa_cobranca (renovacao_id, numero, status) VALUES (?, ?, ?)",
        RENOVACAO_ID,
        1,
        StatusTentativa.PENDENTE.name());
  }

  private void publicar(String topico, String payload) {
    try {
      kafkaTemplate.send(topico, ASSINATURA_ID, payload).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new AssertionError("Falha ao publicar no topico " + topico, e);
    }
  }

  private String payloadCancelamentoAgendado() {
    return jsonMapper.writeValueAsString(
        new CancelamentoAgendado(
            EVENT_ID,
            Instant.parse("2026-08-02T12:00:00Z"),
            UUID.fromString(ASSINATURA_ID),
            StatusAssinatura.ATIVA,
            LocalDate.parse("2026-09-01")));
  }

  private String payloadAssinaturaCancelada() {
    return jsonMapper.writeValueAsString(
        new AssinaturaCancelada(
            EVENT_ID,
            Instant.parse("2026-08-02T12:00:00Z"),
            UUID.fromString(ASSINATURA_ID),
            StatusAssinatura.CANCELADA,
            null));
  }

  private String aguardarStatusDaTentativa(String statusEsperado) {
    long limite = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
    while (System.currentTimeMillis() < limite) {
      String status = statusDaTentativa();
      if (statusEsperado.equals(status)) {
        return status;
      }
      dormir(Duration.ofMillis(200));
    }
    return statusDaTentativa();
  }

  private void aguardarConsumoDoEvento() {
    dormir(Duration.ofSeconds(3));
  }

  private void dormir(Duration duracao) {
    try {
      Thread.sleep(duracao.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("aguarda do teste interrompida", e);
    }
  }

  private String statusDaTentativa() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM tentativa_cobranca WHERE renovacao_id = ?", String.class, RENOVACAO_ID);
  }

  private Integer contarEventosProcessados() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM cancelamento_evento_processado WHERE event_id = ?",
        Integer.class,
        EVENT_ID);
  }

  @AfterEach
  void limparTabelas() {
    jdbcTemplate.update("DELETE FROM cancelamento_evento_processado");
    jdbcTemplate.update("DELETE FROM outbox");
    jdbcTemplate.update("DELETE FROM tentativa_cobranca");
    jdbcTemplate.update("DELETE FROM pagamento_renovacao");
  }
}
