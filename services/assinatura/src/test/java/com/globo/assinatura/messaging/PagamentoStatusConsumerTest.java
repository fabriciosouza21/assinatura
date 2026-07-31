package com.globo.assinatura.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.globo.assinatura.assinatura.ProcessarPagamento;
import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste unitario do {@link PagamentoStatusConsumer}.
 *
 * <p>Verifica a desserializacao e delegacao ao command para eventos validos, e o lancamento de
 * {@link EventoInvalidoException} para eventos com campos obrigatorios ausentes ou payload mal
 * formado.
 */
@ExtendWith(MockitoExtension.class)
class PagamentoStatusConsumerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private ProcessarPagamento processarPagamento;
  @Captor private ArgumentCaptor<PagamentoStatusAtualizado> eventoCaptor;
  private PagamentoStatusConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new PagamentoStatusConsumer(processarPagamento, jsonMapper);
  }

  @Test
  @DisplayName("Deve delegar ao command quando o evento e valido")
  void deveDelegarAoCommandQuandoEventoValido() {
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-07-30T12:00:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            StatusPagamento.APPROVED,
            UUID.fromString("00000000-0000-0000-0000-000000000021"));
    String payload = jsonMapper.writeValueAsString(evento);

    consumer.consumir(payload);

    verify(processarPagamento).executar(eventoCaptor.capture());
    PagamentoStatusAtualizado processado = eventoCaptor.getValue();
    assertThat(processado.assinaturaId())
        .as("assinaturaId desserializado")
        .isEqualTo(evento.assinaturaId());
    assertThat(processado.status()).as("status desserializado").isEqualTo(StatusPagamento.APPROVED);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o status esta ausente")
  void deveLancarEventoInvalidoQuandoStatusAusente() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "status": null,
          "paymentId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem status rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("status");

    verifyNoInteractions(processarPagamento);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o eventId esta ausente")
  void deveLancarEventoInvalidoQuandoEventIdAusente() {
    String payload =
        """
        {
          "eventId": null,
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "status": "APPROVED",
          "paymentId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem eventId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("eventId");

    verifyNoInteractions(processarPagamento);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o assinaturaId esta ausente")
  void deveLancarEventoInvalidoQuandoAssinaturaIdAusente() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "assinaturaId": null,
          "status": "APPROVED",
          "paymentId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem assinaturaId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("assinaturaId");

    verifyNoInteractions(processarPagamento);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o payload e mal formado")
  void deveLancarEventoInvalidoQuandoPayloadMalFormado() {
    String payloadMalFormado = "{isto-nao-e-json";

    assertThatThrownBy(() -> consumer.consumir(payloadMalFormado))
        .as("Payload mal formado rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("mal formado");

    verifyNoInteractions(processarPagamento);
  }
}
