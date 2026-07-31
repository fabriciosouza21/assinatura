package com.globo.assinatura.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.messaging.RotasEventoTopicoProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

  @Mock private OutboxRepository outboxRepository;

  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher =
        new OutboxPublisher(
            outboxRepository,
            kafkaTemplate,
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMillis(500)),
            new RotasEventoTopicoProperties(
                Map.of(
                    "AssinaturaSolicitada", "assinatura-solicitada",
                    "RenovacaoSolicitada", "renovacao-solicitada")),
            100);
  }

  @Test
  @DisplayName("Deve publicar evento pendente e marcar como publicado")
  void deveMarcarComoPublicadoAoPublicarPendente() {
    OutboxEvent evento = eventoPendente("AssinaturaSolicitada");
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    assertThat(capturado.getValue().getStatus())
        .as("Evento transita para PUBLICADO")
        .isEqualTo(OutboxStatus.PUBLICADO);
  }

  @Test
  @DisplayName("Deve publicar com key igual ao aggregateId e o payload do evento")
  void deveUsarAggregateIdComoKeyComPayloadDoEvento() {
    OutboxEvent evento = eventoPendente("AssinaturaSolicitada");
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    verify(kafkaTemplate)
        .send("assinatura-solicitada", evento.getAggregateId().toString(), evento.getPayload());
  }

  @Test
  @DisplayName("Deve rotear RenovacaoSolicitada para o topico de renovacao")
  void deveRotearRenovacaoSolicitadaParaTopicoDeRenovacao() {
    OutboxEvent evento = eventoPendente("RenovacaoSolicitada");
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    verify(kafkaTemplate)
        .send("renovacao-solicitada", evento.getAggregateId().toString(), evento.getPayload());
  }

  @Test
  @DisplayName("Deve tratar como falha evento cujo eventType nao tem rota mapeada")
  void deveTratarComoFalhaEventoSemRotaMapeada() {
    OutboxEvent evento = eventoPendente("EventoFantasma");
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    verify(kafkaTemplate, never()).send(any(), any(), any());
    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent salvo = capturado.getValue();
    assertThat(salvo.getStatus())
        .as("Evento sem rota permanece PENDENTE, nao e descartado")
        .isEqualTo(OutboxStatus.PENDENTE);
    assertThat(salvo.getTentativas())
        .as("Falha de rota conta como tentativa da politica de retry")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Deve reagendar evento pendente quando o envio falha com tentativas restantes")
  void deveReagendarEventoPendenteQuandoEnvioFalha() {
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));
    OutboxEvent evento = eventoPendente("AssinaturaSolicitada");
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    OutboxEvent salvo = capturado.getValue();
    assertThat(salvo.getStatus())
        .as("Evento permanece PENDENTE para reenvio")
        .isEqualTo(OutboxStatus.PENDENTE);
    assertThat(salvo.getTentativas()).as("Tentativas incrementadas").isEqualTo(1);
    assertThat(salvo.getProximaTentativaEm())
        .as("Proxima tentativa reagendada no futuro")
        .isAfter(Instant.now());
  }

  @Test
  @DisplayName("Deve persistir o resultado do envio na mesma thread da transacao do publisher")
  void devePersistirResultadoNaMesmaThreadDaTransacao() throws Exception {
    final String threadDoPublisher = Thread.currentThread().getName();
    // Simula um produtor Kafka real: o ack chega noutra thread, depois do envio.
    CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> ack =
        new CompletableFuture<>();
    when(kafkaTemplate.send(any(), any(), any())).thenReturn(ack);
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(eventoPendente("AssinaturaSolicitada")));
    java.util.concurrent.atomic.AtomicReference<String> threadDoSave =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(outboxRepository.save(any()))
        .thenAnswer(
            inv -> {
              threadDoSave.set(Thread.currentThread().getName());
              return inv.getArgument(0);
            });
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      // Completa o ack noutra thread apos o publisher comecar a esperar.
      executor.submit(
          () -> {
            try {
              Thread.sleep(50);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            ack.complete(null);
          });
      publisher.publicarPendentes();
    } finally {
      executor.shutdownNow();
    }

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    assertThat(capturado.getValue().getStatus())
        .as("Evento transita para PUBLICADO")
        .isEqualTo(OutboxStatus.PUBLICADO);
    assertThat(threadDoSave.get())
        .as("O save deve rodar na thread da transacao, nao numa thread de callback")
        .isEqualTo(threadDoPublisher);
  }

  @Test
  @DisplayName("Deve marcar como falha ao esgotar tentativas na terceira falha")
  void deveMarcarComoFalhaAoEsgotarTentativas() {
    when(kafkaTemplate.send(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("indisponivel")));
    OutboxEvent evento = eventoPendente("AssinaturaSolicitada");
    evento.registrarFalha("erro1", Instant.now());
    evento.registrarFalha("erro2", Instant.now());
    when(outboxRepository.buscarPublicaveis(any(Instant.class), eq(100)))
        .thenReturn(List.of(evento));

    publisher.publicarPendentes();

    ArgumentCaptor<OutboxEvent> capturado = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxRepository).save(capturado.capture());
    assertThat(capturado.getValue().getStatus())
        .as("Terceira falha marca o evento como FALHA")
        .isEqualTo(OutboxStatus.FALHA);
  }

  private static OutboxEvent eventoPendente(String eventType) {
    return OutboxEvent.criar(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "Assinatura",
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        eventType,
        "{}");
  }
}
