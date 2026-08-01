package com.globo.assinatura.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.globo.assinatura.assinatura.ProcessarRenovacaoResultado;
import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import com.globo.assinatura.messaging.event.RenovacaoTentativasEsgotadas;
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
 * Teste unitario do {@link RenovacaoResultadoConsumer}.
 *
 * <p>Verifica a desserializacao por campo discriminador {@code tipo}, a delegacao ao command para
 * eventos validos e o lancamento de {@link EventoInvalidoException} para payload mal formado, sem
 * tipo, com tipo desconhecido ou com campos obrigatorios ausentes.
 */
@ExtendWith(MockitoExtension.class)
class RenovacaoResultadoConsumerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private ProcessarRenovacaoResultado processarRenovacaoResultado;
  @Captor private ArgumentCaptor<PagamentoRenovacaoAprovado> aprovadoCaptor;
  @Captor private ArgumentCaptor<RenovacaoTentativasEsgotadas> esgotadoCaptor;
  private RenovacaoResultadoConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new RenovacaoResultadoConsumer(processarRenovacaoResultado, jsonMapper);
  }

  @Test
  @DisplayName("Deve delegar ao command quando o evento e do tipo APROVADO")
  void deveDelegarAoCommandQuandoEventoAprovado() {
    UUID renovacaoId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    String payload =
        """
        {
          "tipo": "APROVADO",
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021",
          "paymentId": "pay_123",
          "cicloReferencia": 2
        }
        """;

    consumer.consumir(payload);

    verify(processarRenovacaoResultado).executar(aprovadoCaptor.capture());
    PagamentoRenovacaoAprovado processado = aprovadoCaptor.getValue();
    assertThat(processado.renovacaoId())
        .as("renovacaoId desserializado no evento aprovado")
        .isEqualTo(renovacaoId);
    assertThat(processado.paymentId()).as("paymentId desserializado").isEqualTo("pay_123");
  }

  @Test
  @DisplayName("Deve delegar ao command quando o evento e do tipo ESGOTADO")
  void deveDelegarAoCommandQuandoEventoEsgotado() {
    UUID renovacaoId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    String payload =
        """
        {
          "tipo": "ESGOTADO",
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021",
          "cicloReferencia": 2
        }
        """;

    consumer.consumir(payload);

    verify(processarRenovacaoResultado).executar(esgotadoCaptor.capture());
    RenovacaoTentativasEsgotadas processado = esgotadoCaptor.getValue();
    assertThat(processado.renovacaoId())
        .as("renovacaoId desserializado no evento esgotado")
        .isEqualTo(renovacaoId);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o tipo esta ausente")
  void deveLancarEventoInvalidoQuandoTipoAusente() {
    String payload =
        """
        {
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem tipo rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("tipo");

    verifyNoInteractions(processarRenovacaoResultado);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o tipo e desconhecido")
  void deveLancarEventoInvalidoQuandoTipoDesconhecido() {
    String payload =
        """
        {
          "tipo": "INDEFINIDO",
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento com tipo desconhecido rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("tipo");

    verifyNoInteractions(processarRenovacaoResultado);
  }

  @Test
  @DisplayName("Deve rejeitar tipo desconhecido sem ecoar o valor do payload na mensagem")
  void deveRejeitarTipoDesconhecidoSemEcoarValorDoPayload() {
    String payload =
        """
        {
          "tipo": "EVIL\\nINFO fake-log-line",
          "eventId": "00000000-0000-0000-0000-000000000001",
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021"
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("valor nao confiavel do tipo nao deve ser interpolado na mensagem da excecao")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageNotContaining("EVIL")
        .hasMessageNotContaining("\n");

    verifyNoInteractions(processarRenovacaoResultado);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o payload e mal formado")
  void deveLancarEventoInvalidoQuandoPayloadMalFormado() {
    String payloadMalFormado = "{isto-nao-e-json";

    assertThatThrownBy(() -> consumer.consumir(payloadMalFormado))
        .as("Payload mal formado rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("mal formado");

    verifyNoInteractions(processarRenovacaoResultado);
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando o eventId esta ausente")
  void deveLancarEventoInvalidoQuandoEventIdAusente() {
    String payload =
        """
        {
          "tipo": "APROVADO",
          "eventId": null,
          "ocorridoEm": "2026-07-30T12:00:00Z",
          "renovacaoId": "00000000-0000-0000-0000-000000000011",
          "assinaturaId": "00000000-0000-0000-0000-000000000021",
          "paymentId": "pay_123",
          "cicloReferencia": 2
        }
        """;

    assertThatThrownBy(() -> consumer.consumir(payload))
        .as("Evento sem eventId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("eventId");

    verifyNoInteractions(processarRenovacaoResultado);
  }
}
