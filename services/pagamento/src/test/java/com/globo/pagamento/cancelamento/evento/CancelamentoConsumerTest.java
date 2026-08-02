package com.globo.pagamento.cancelamento.evento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cancelamento.CancelarTentativasPendentes;
import com.globo.pagamento.cancelamento.idempotencia.CancelamentoEventoProcessadoRepository;
import com.globo.pagamento.messaging.EventoInvalidoException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** Teste unitario do {@link CancelamentoConsumer}. */
@ExtendWith(MockitoExtension.class)
class CancelamentoConsumerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private CancelarTentativasPendentes cancelarTentativasPendentes;
  @Mock private CancelamentoEventoProcessadoRepository eventoProcessadoRepository;
  @Captor private ArgumentCaptor<String> assinaturaIdCaptor;
  @Captor private ArgumentCaptor<UUID> eventIdCaptor;
  @Captor private ArgumentCaptor<UUID> assinaturaEventoCaptor;
  private CancelamentoConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new CancelamentoConsumer(
            jsonMapper, cancelarTentativasPendentes, eventoProcessadoRepository);
  }

  @Test
  @DisplayName("Nao deve cancelar tentativas ao receber cancelamento agendado")
  void naoDeveCancelarTentativasNoCancelamentoAgendado() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID assinaturaId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-08-02T12:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000011",
          "status": "ATIVA",
          "fimCiclo": "2026-09-01"
        }
        """;

    consumer.consumirCancelamentoAgendado(payload);

    verifyNoInteractions(eventoProcessadoRepository, cancelarTentativasPendentes);
  }

  @Test
  @DisplayName("Deve delegar a assinatura cancelada para as tentativas pendentes")
  void deveDelegarAssinaturaCanceladaParaTentativasPendentes() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID assinaturaId = UUID.fromString("00000000-0000-0000-0000-000000000012");
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000002",
          "ocorridoEm": "2026-08-02T13:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000012",
          "status": "CANCELADA",
          "fimCiclo": null
        }
        """;
    when(eventoProcessadoRepository.registrarSeNovo(eventId, assinaturaId)).thenReturn(1);

    consumer.consumirAssinaturaCancelada(payload);

    verificarRegistroAntesDaDelegacao(eventId, assinaturaId);
  }

  @Test
  @DisplayName("Nao deve delegar assinatura cancelada duplicada")
  void naoDeveDelegarAssinaturaCanceladaDuplicada() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID assinaturaId = UUID.fromString("00000000-0000-0000-0000-000000000012");
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000002",
          "ocorridoEm": "2026-08-02T13:00:00Z",
          "assinaturaId": "00000000-0000-0000-0000-000000000012",
          "status": "CANCELADA",
          "fimCiclo": null
        }
        """;
    when(eventoProcessadoRepository.registrarSeNovo(eventId, assinaturaId)).thenReturn(0);

    consumer.consumirAssinaturaCancelada(payload);

    verifyNoInteractions(cancelarTentativasPendentes);
    verificarEventoProcessado(eventId, assinaturaId);
  }

  @ParameterizedTest(name = "Campo obrigatorio ausente: {0}")
  @MethodSource("payloadsSemCamposObrigatorios")
  @DisplayName("Deve rejeitar cancelamento agendado sem campo obrigatorio")
  void deveRejeitarCancelamentoAgendadoSemCampoObrigatorio(String campo, String payload) {
    assertThatThrownBy(() -> consumer.consumirCancelamentoAgendado(payload))
        .as("Evento sem %s rejeitado", campo)
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining(campo);

    verifyNoInteractions(eventoProcessadoRepository, cancelarTentativasPendentes);
  }

  @ParameterizedTest(name = "Campo obrigatorio ausente: {0}")
  @MethodSource("payloadsSemCamposObrigatorios")
  @DisplayName("Deve rejeitar assinatura cancelada sem campo obrigatorio")
  void deveRejeitarAssinaturaCanceladaSemCampoObrigatorio(String campo, String payload) {
    assertThatThrownBy(() -> consumer.consumirAssinaturaCancelada(payload))
        .as("Evento sem %s rejeitado", campo)
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining(campo);

    verifyNoInteractions(eventoProcessadoRepository, cancelarTentativasPendentes);
  }

  private static Stream<Arguments> payloadsSemCamposObrigatorios() {
    return Stream.of(
        Arguments.of(
            "eventId",
            """
            {
              "ocorridoEm": "2026-08-02T12:00:00Z",
              "assinaturaId": "00000000-0000-0000-0000-000000000011",
              "status": "ATIVA",
              "fimCiclo": "2026-09-01"
            }
            """),
        Arguments.of(
            "ocorridoEm",
            """
            {
              "eventId": "00000000-0000-0000-0000-000000000001",
              "assinaturaId": "00000000-0000-0000-0000-000000000011",
              "status": "ATIVA",
              "fimCiclo": "2026-09-01"
            }
            """),
        Arguments.of(
            "assinaturaId",
            """
            {
              "eventId": "00000000-0000-0000-0000-000000000001",
              "ocorridoEm": "2026-08-02T12:00:00Z",
              "status": "ATIVA",
              "fimCiclo": "2026-09-01"
            }
            """),
        Arguments.of(
            "status",
            """
            {
              "eventId": "00000000-0000-0000-0000-000000000001",
              "ocorridoEm": "2026-08-02T12:00:00Z",
              "assinaturaId": "00000000-0000-0000-0000-000000000011",
              "fimCiclo": "2026-09-01"
            }
            """));
  }

  private void verificarEventoProcessado(UUID eventId, UUID assinaturaId) {
    verify(eventoProcessadoRepository)
        .registrarSeNovo(eventIdCaptor.capture(), assinaturaEventoCaptor.capture());
    assertThat(eventIdCaptor.getValue()).as("eventId registrado").isEqualTo(eventId);
    assertThat(assinaturaEventoCaptor.getValue())
        .as("assinatura do evento registrada")
        .isEqualTo(assinaturaId);
  }

  private void verificarRegistroAntesDaDelegacao(UUID eventId, UUID assinaturaId) {
    InOrder inOrder = inOrder(eventoProcessadoRepository, cancelarTentativasPendentes);
    inOrder
        .verify(eventoProcessadoRepository)
        .registrarSeNovo(eventIdCaptor.capture(), assinaturaEventoCaptor.capture());
    assertThat(eventIdCaptor.getValue()).as("eventId registrado").isEqualTo(eventId);
    assertThat(assinaturaEventoCaptor.getValue())
        .as("assinatura do evento registrada")
        .isEqualTo(assinaturaId);
    inOrder.verify(cancelarTentativasPendentes).executar(assinaturaIdCaptor.capture());
    assertThat(assinaturaIdCaptor.getValue())
        .as("assinatura encaminhada para cancelamento das tentativas")
        .isEqualTo(assinaturaId.toString());
  }
}
