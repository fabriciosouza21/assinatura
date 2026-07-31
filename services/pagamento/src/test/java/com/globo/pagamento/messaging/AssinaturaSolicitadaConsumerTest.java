package com.globo.pagamento.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.globo.pagamento.cobranca.CriarCobrancaService;
import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.messaging.event.AssinaturaSolicitada;
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
 * Teste unitario do {@link AssinaturaSolicitadaConsumer}.
 *
 * <p>Verifica a desserializacao e delegacao ao service para eventos validos, e o lancamento de
 * {@link EventoInvalidoException} para eventos com campos obrigatorios ausentes.
 */
@ExtendWith(MockitoExtension.class)
class AssinaturaSolicitadaConsumerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private CriarCobrancaService criarCobrancaService;
  @Captor private ArgumentCaptor<AssinaturaSolicitada> eventoCaptor;
  private AssinaturaSolicitadaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new AssinaturaSolicitadaConsumer(jsonMapper, criarCobrancaService);
  }

  @Test
  @DisplayName("Deve delegar ao service quando o evento e valido")
  void deveDelegarAoServiceQuandoEventoValido() {
    AssinaturaSolicitada evento =
        new AssinaturaSolicitada(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-07-30T12:00:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            UUID.fromString("00000000-0000-0000-0000-000000000021"),
            Plano.BASICO,
            new BigDecimal("19.90"));
    String payload = jsonMapper.writeValueAsString(evento);

    consumer.consumir(payload);

    verify(criarCobrancaService).processar(eventoCaptor.capture());
    AssinaturaSolicitada processado = eventoCaptor.getValue();
    assertThat(processado.assinaturaId())
        .as("assinaturaId desserializado")
        .isEqualTo(evento.assinaturaId());
    assertThat(processado.valor())
        .as("valor em reais desserializado")
        .isEqualByComparingTo(evento.valor());
    assertThat(processado.plano()).as("plano desserializado").isEqualTo(Plano.BASICO);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando campo obrigatorio esta ausente")
  void deveLancarEventoInvalidoQuandoCampoObrigatorioAusente() {
    AssinaturaSolicitada semAssinaturaId =
        new AssinaturaSolicitada(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-07-30T12:00:00Z"),
            null,
            UUID.fromString("00000000-0000-0000-0000-000000000021"),
            Plano.BASICO,
            new BigDecimal("19.90"));
    String payload = jsonMapper.writeValueAsString(semAssinaturaId);

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem assinaturaId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("assinaturaId");

    verifyNoInteractions(criarCobrancaService);
  }

  @Test
  @DisplayName("Plano invalido deve produzir erro de plano, nao de evento mal formado")
  void planoInvalidoDeveProduzirErroDePlano() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "usuarioId": "00000000-0000-0000-0000-000000000021",
          "plano": "GOLD",
          "valor": 19.90
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Plano invalido sinalizado como campo ausente, nao como evento mal formado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessage("plano ausente");

    verifyNoInteractions(criarCobrancaService);
  }

  @Test
  @DisplayName("Valor nao positivo deve ser rejeitado antes de chamar o gateway")
  void valorNaoPositivoDeveSerRejeitado() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "usuarioId": "00000000-0000-0000-0000-000000000021",
          "plano": "BASICO",
          "valor": -19.90
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Valor nao positivo rejeitado na validacao")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("valor");

    verifyNoInteractions(criarCobrancaService);
  }
}
