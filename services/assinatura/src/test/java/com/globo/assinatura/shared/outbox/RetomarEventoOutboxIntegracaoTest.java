package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integracao da retomada manual concorrente contra o Postgres real.
 *
 * <p>Garante a AC de concorrencia da recuperacao manual: duas chamadas simultaneas sobre o mesmo
 * evento serializam no lock da linha e somente uma efetiva a transicao; a outra observa o status ja
 * transicionado e e rejeitada com {@link OutboxEventoNaoRetomavelException}. A classe nao participa
 * de transacao de teste para que cada chamada concorrente abra a propria transacao.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@Import({RetomarEventoOutbox.class, OutboxConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class RetomarEventoOutboxIntegracaoTest {

  @Autowired private OutboxRepository outboxRepository;

  @Autowired private RetomarEventoOutbox retomar;

  @Test
  @DisplayName("Deve efetivar somente uma das retomadas concorrentes sobre o mesmo evento")
  void deveEfetivarSomenteUmaRetomadaConcorrente() throws Exception {
    UUID eventId = UUID.randomUUID();
    OutboxEvent evento =
        OutboxEvent.criar(eventId, "Assinatura", UUID.randomUUID(), "AssinaturaSolicitada", "{}");
    evento.marcarFalha("timeout", Instant.now().minusSeconds(3700));
    outboxRepository.saveAndFlush(evento);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<OutboxEvent>> futuros = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futuros.add(executor.submit(() -> retomar.executar(eventId, "admin@example.com")));
      }

      int efetivadas = 0;
      Throwable rejeicao = null;
      for (Future<OutboxEvent> futuro : futuros) {
        try {
          futuro.get(30, TimeUnit.SECONDS);
          efetivadas++;
        } catch (ExecutionException e) {
          rejeicao = e.getCause();
        }
      }

      assertThat(efetivadas).as("Somente uma retomada efetiva a transicao").isEqualTo(1);
      assertThat(rejeicao)
          .as("A retomada concorrente e rejeitada com 409")
          .isInstanceOf(OutboxEventoNaoRetomavelException.class);
      assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
          .as("Evento persistido em RETENTATIVA_DLQ apos a retomada vencedora")
          .isEqualTo(OutboxStatus.RETENTATIVA_DLQ);
      assertThat(outboxRepository.findById(eventId).orElseThrow().getCiclosRecuperacao())
          .as("Ciclo de recuperacao incrementado uma unica vez")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }
}
