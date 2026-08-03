package com.globo.assinatura.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.shared.outbox.api.OutboxFalhaLista;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ListarFalhasOutboxTest {

  @Mock private OutboxRepository outboxRepository;

  private ListarFalhasOutbox listar;

  @BeforeEach
  void setUp() {
    listar = new ListarFalhasOutbox(outboxRepository);
  }

  @Test
  @DisplayName("Deve repassar tipo, idade minima e paginacao ao repositorio")
  void deveRepassarFiltrosAoRepositorio() {
    when(outboxRepository.buscarFalhas(
            eq("AssinaturaSolicitada"), any(Instant.class), any(Pageable.class)))
        .thenReturn(paginaVazia(1, 10));

    listar.executar("AssinaturaSolicitada", 3600L, 1, 10);

    ArgumentCaptor<Instant> limiteCapturado = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Pageable> pageableCapturado = ArgumentCaptor.forClass(Pageable.class);
    verify(outboxRepository)
        .buscarFalhas(
            eq("AssinaturaSolicitada"), limiteCapturado.capture(), pageableCapturado.capture());
    assertThat(limiteCapturado.getValue())
        .as("Limite de idade e aproximadamente agora menos 3600s")
        .isCloseTo(Instant.now().minusSeconds(3600), byLessThan(2, ChronoUnit.SECONDS));
    assertThat(pageableCapturado.getValue())
        .as("Paginacao repassada com pagina e tamanho informados")
        .isEqualTo(PageRequest.of(1, 10));
  }

  @Test
  @DisplayName("Deve mapear os itens com os campos de diagnostico da falha")
  void deveMapearItensComCamposDeDiagnostico() {
    OutboxEvent evento = eventoEmFalha();
    Page<OutboxEvent> pagina = new PageImpl<>(List.of(evento), PageRequest.of(0, 20), 1);
    when(outboxRepository.buscarFalhas(any(), any(Instant.class), any(Pageable.class)))
        .thenReturn(pagina);

    OutboxFalhaLista resultado = listar.executar(null, 0L, 0, 20);

    assertThat(resultado.items()).as("Pagina com o evento em falha").hasSize(1);
    assertThat(resultado.items().get(0).eventId())
        .as("eventId do item")
        .isEqualTo(evento.getEventId());
    assertThat(resultado.items().get(0).eventType())
        .as("Tipo de evento do item")
        .isEqualTo(evento.getEventType());
    assertThat(resultado.items().get(0).falhouEm())
        .as("Instante da falha do item")
        .isEqualTo(evento.getFalhouEm());
    assertThat(resultado.items().get(0).ciclosRecuperacao())
        .as("Contador de ciclos do evento terminal")
        .isEqualTo(3);
    assertThat(resultado.total()).as("Total de eventos em falha").isEqualTo(1);
  }

  private static Page<OutboxEvent> paginaVazia(int page, int size) {
    return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
  }

  private static OutboxEvent eventoEmFalha() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "Assinatura",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "AssinaturaSolicitada",
            "{}");
    for (int ciclo = 0; ciclo < 3; ciclo++) {
      evento.recuperarParaRetentativa(Instant.now().plusSeconds(1));
      evento.marcarFalha("timeout", Instant.now());
    }
    return evento;
  }
}
