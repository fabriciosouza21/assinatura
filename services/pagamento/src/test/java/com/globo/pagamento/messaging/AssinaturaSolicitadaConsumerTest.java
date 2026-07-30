package com.globo.pagamento.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.globo.pagamento.cobranca.CriarCobrancaService;
import com.globo.pagamento.messaging.event.AssinaturaSolicitada;
import java.math.BigDecimal;
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

  @Mock private CriarCobrancaService criarCobrancaService;
  @Captor private ArgumentCaptor<AssinaturaSolicitada> eventoCaptor;
  private AssinaturaSolicitadaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new AssinaturaSolicitadaConsumer(
            JsonMapper.builder().findAndAddModules().build(), criarCobrancaService);
  }

  @Test
  @DisplayName("Deve delegar ao service quando o evento e valido")
  void deveDelegarAoServiceQuandoEventoValido() {
    String payload =
        "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"ocorridoEm\":\"2026-07-30T12:00:00Z\","
            + "\"assinaturaId\":\"00000000-0000-0000-0000-000000000011\","
            + "\"usuarioId\":\"00000000-0000-0000-0000-000000000021\","
            + "\"plano\":\"BASICO\",\"valor\":19.90}";

    consumer.consumir(payload);

    verify(criarCobrancaService).processar(eventoCaptor.capture());
    AssinaturaSolicitada evento = eventoCaptor.getValue();
    assertThat(evento.assinaturaId())
        .as("assinaturaId desserializado")
        .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000011"));
    assertThat(evento.valor())
        .as("valor em reais desserializado")
        .isEqualByComparingTo(new BigDecimal("19.90"));
    assertThat(evento.plano().name()).as("plano desserializado").isEqualTo("BASICO");
  }

  @Test
  @DisplayName("Deve lancar evento invalido quando campo obrigatorio esta ausente")
  void deveLancarEventoInvalidoQuandoCampoObrigatorioAusente() {
    String payloadSemAssinatura =
        "{\"eventId\":\"00000000-0000-0000-0000-000000000001\","
            + "\"ocorridoEm\":\"2026-07-30T12:00:00Z\","
            + "\"usuarioId\":\"00000000-0000-0000-0000-000000000021\","
            + "\"plano\":\"BASICO\",\"valor\":19.90}";

    assertThatThrownBy(() -> consumer.consumir(payloadSemAssinatura))
        .as("Evento sem assinaturaId rejeitado")
        .isInstanceOf(EventoInvalidoException.class)
        .hasMessageContaining("assinaturaId");

    verifyNoInteractions(criarCobrancaService);
  }
}
