package com.globo.pagamento.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.messaging.event.RenovacaoSolicitada;
import com.globo.pagamento.renovacao.CriarPagamentoRenovacaoService;
import java.math.BigDecimal;
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
 * Teste unitario do {@link RenovacaoSolicitadaConsumer}.
 *
 * <p>Verifica a desserializacao e delegacao ao service para eventos validos, e o lancamento de
 * {@link EventoInvalidoException} para eventos com campos obrigatorios ausentes ou invalidos.
 */
@ExtendWith(MockitoExtension.class)
class RenovacaoSolicitadaConsumerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private CriarPagamentoRenovacaoService criarPagamentoRenovacaoService;
  @Captor private ArgumentCaptor<RenovacaoSolicitada> eventoCaptor;
  private RenovacaoSolicitadaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new RenovacaoSolicitadaConsumer(jsonMapper, criarPagamentoRenovacaoService);
  }

  @Test
  @DisplayName("Deve delegar ao service quando o evento e valido")
  void deveDelegarAoServiceQuandoEventoEhValido() {
    RenovacaoSolicitada evento = eventoValido();
    String payload = jsonMapper.writeValueAsString(evento);

    consumer.consumir(payload);

    verify(criarPagamentoRenovacaoService).processar(eventoCaptor.capture());
    RenovacaoSolicitada processado = eventoCaptor.getValue();
    assertThat(processado.renovacaoId())
        .as("renovacaoId desserializado")
        .isEqualTo(evento.renovacaoId());
    assertThat(processado.cicloReferencia())
        .as("cicloReferencia desserializado")
        .isEqualTo(evento.cicloReferencia());
    assertThat(processado.valor())
        .as("valor em reais desserializado")
        .isEqualByComparingTo(evento.valor());
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando renovacaoId esta ausente")
  void deveLancarEventoInvalidoQuandoRenovacaoIdAusente() {
    RenovacaoSolicitada semRenovacaoId =
        new RenovacaoSolicitada(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-07-31T12:00:00Z"),
            null,
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            Plano.BASICO,
            new BigDecimal("19.90"),
            2);
    String payload = jsonMapper.writeValueAsString(semRenovacaoId);

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem renovacaoId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("renovacaoId");

    verifyNoInteractions(criarPagamentoRenovacaoService);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando eventId esta ausente")
  void deveLancarEventoInvalidoQuandoEventIdAusente() {
    RenovacaoSolicitada semEventId =
        new RenovacaoSolicitada(
            null,
            Instant.parse("2026-07-31T12:00:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000031"),
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            Plano.BASICO,
            new BigDecimal("19.90"),
            2);
    String payload = jsonMapper.writeValueAsString(semEventId);

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem eventId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("eventId");

    verifyNoInteractions(criarPagamentoRenovacaoService);
  }

  @Test
  @DisplayName("Valor nao positivo deve ser rejeitado antes de persistir")
  void valorNaoPositivoDeveSerRejeitado() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-31T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000031",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "plano": "BASICO",
          "valor": -19.90,
          "cicloReferencia": 2
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Valor nao positivo rejeitado na validacao")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("valor");

    verifyNoInteractions(criarPagamentoRenovacaoService);
  }

  @Test
  @DisplayName("Ciclo de referencia nao positivo deve ser rejeitado antes de persistir")
  void cicloReferenciaNaoPositivoDeveSerRejeitado() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-31T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000031",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "plano": "BASICO",
          "valor": 19.90,
          "cicloReferencia": 0
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Ciclo de referencia nao positivo rejeitado na validacao")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("cicloReferencia");

    verifyNoInteractions(criarPagamentoRenovacaoService);
  }

  private RenovacaoSolicitada eventoValido() {
    return new RenovacaoSolicitada(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        Instant.parse("2026-07-31T12:00:00Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000031"),
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        Plano.BASICO,
        new BigDecimal("19.90"),
        2);
  }
}
