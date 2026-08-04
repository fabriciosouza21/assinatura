package com.globo.pagamento.adesao.agendador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.adesao.CobrancaAdesaoTentativa;
import com.globo.pagamento.adesao.CobrancaAdesaoTentativaRepository;
import com.globo.pagamento.adesao.StatusTentativaAdesao;
import com.globo.pagamento.cobranca.CobrancaRepository;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.util.List;
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
 * Teste unitario do {@link CobrancaAdesaoScheduler}.
 *
 * <p>Verifica que o scheduler cobra as tentativas de adesao prontas, persiste o {@code paymentId}
 * devolvido pelo gateway na tentativa e na correlacao, e contabiliza falhas tecnicas contra o teto
 * configurado.
 */
@ExtendWith(MockitoExtension.class)
class CobrancaAdesaoSchedulerTest {

  private static final int TETO_FALHAS_TECNICAS = 3;
  private static final long BACKOFF_FALHAS_TECNICAS_MS = 60000L;
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";

  @Mock private CobrancaAdesaoTentativaRepository tentativaRepository;
  @Mock private CobrancaRepository cobrancaRepository;
  @Mock private GatewayPagamentoClient gateway;
  @Mock private OutboxRepository outboxRepository;
  @Captor private ArgumentCaptor<CobrancaAdesaoTentativa> tentativaCaptor;
  @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  private CobrancaAdesaoScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new CobrancaAdesaoScheduler(
            tentativaRepository,
            cobrancaRepository,
            gateway,
            outboxRepository,
            jsonMapper,
            TETO_FALHAS_TECNICAS,
            BACKOFF_FALHAS_TECNICAS_MS);
  }

  @Test
  @DisplayName("Deve registrar cobranca e correlacao quando o gateway aceita")
  void deveRegistrarCobrancaQuandoGatewayAceita() {
    when(tentativaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaPendenteSemPaymentId()));
    when(gateway.criarCobranca(eq(ASSINATURA_ID), any())).thenReturn(new CobrancaCriada("pay-123"));

    scheduler.cobrar();

    verify(gateway).criarCobranca(eq(ASSINATURA_ID), eq(new BigDecimal("99.90")));
    verify(tentativaRepository).save(tentativaCaptor.capture());
    CobrancaAdesaoTentativa persistida = tentativaCaptor.getValue();
    assertThat(persistida.getStatus())
        .as("Status apos cobranca com sucesso")
        .isEqualTo(StatusTentativaAdesao.COBRADA);
    assertThat(persistida.getPaymentId()).as("PaymentId registrado").isEqualTo("pay-123");
    verify(cobrancaRepository).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve contabilizar falha tecnica e agendar proxima abaixo do teto")
  void deveContabilizarFalhaTecnicaAbaixoDoTeto() {
    when(tentativaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaPendenteSemPaymentId()));
    when(gateway.criarCobranca(any(), any())).thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    verify(tentativaRepository).save(tentativaCaptor.capture());
    CobrancaAdesaoTentativa persistida = tentativaCaptor.getValue();
    assertThat(persistida.getStatus())
        .as("Status mantido apos falha abaixo do teto")
        .isEqualTo(StatusTentativaAdesao.PENDENTE);
    assertThat(persistida.getFalhasTecnicas()).as("Uma falha tecnica contabilizada").isEqualTo(1);
    assertThat(persistida.getProximaTentativaEm())
        .as("Proxima tentativa agendada com backoff")
        .isNotNull();
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve esgotar a tentativa e gravar outbox ao atingir o teto de falhas tecnicas")
  void deveEsgotarTentativaAoAtingirTetoDeFalhasTecnicas() {
    CobrancaAdesaoTentativa tentativa = tentativaPendenteSemPaymentId();
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();
    when(tentativaRepository.buscarProntasParaCobrar()).thenReturn(List.of(tentativa));
    when(gateway.criarCobranca(any(), any())).thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    verify(tentativaRepository).save(tentativaCaptor.capture());
    CobrancaAdesaoTentativa persistida = tentativaCaptor.getValue();
    assertThat(persistida.getStatus())
        .as("Status apos esgotar")
        .isEqualTo(StatusTentativaAdesao.ESGOTADA);
    assertThat(persistida.getFalhasTecnicas())
        .as("Falhas tecnicas no teto")
        .isEqualTo(TETO_FALHAS_TECNICAS);
    verify(outboxRepository).save(outboxCaptor.capture());
    assertThat(outboxCaptor.getValue().getPayload())
        .as("Payload com o identificador da assinatura")
        .contains(ASSINATURA_ID);
  }

  private CobrancaAdesaoTentativa tentativaPendenteSemPaymentId() {
    return new CobrancaAdesaoTentativa(ASSINATURA_ID, new BigDecimal("99.90"));
  }
}
