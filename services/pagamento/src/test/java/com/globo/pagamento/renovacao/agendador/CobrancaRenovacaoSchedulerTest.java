package com.globo.pagamento.renovacao.agendador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
 * Teste unitario do {@link CobrancaRenovacaoScheduler}.
 *
 * <p>Verifica que o scheduler cobra as tentativas prontas e persiste o {@code paymentId} devolvido
 * pelo gateway na tentativa correspondente, contabilizando falhas tecnicas contra o teto
 * configurado.
 */
@ExtendWith(MockitoExtension.class)
class CobrancaRenovacaoSchedulerTest {

  private static final int TETO_FALHAS_TECNICAS = 3;
  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String RENOVACAO_ORFA_ID = "00000000-0000-0000-0000-000000000032";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";

  @Mock private TentativaCobrancaRepository tentativaCobrancaRepository;
  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  @Mock private GatewayPagamentoClient gateway;
  @Mock private OutboxRepository outboxRepository;
  @Captor private ArgumentCaptor<TentativaCobranca> tentativaCaptor;
  @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  private CobrancaRenovacaoScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler =
        new CobrancaRenovacaoScheduler(
            tentativaCobrancaRepository,
            pagamentoRenovacaoRepository,
            gateway,
            outboxRepository,
            jsonMapper,
            TETO_FALHAS_TECNICAS);
  }

  @Test
  @DisplayName("Deve persistir a tentativa com o payment id devolvido")
  void devePersistirTentativaComPaymentId() {
    // Fixture "pronta": tentativa PENDENTE sem paymentId, elegivel para cobranca.
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-123"));

    scheduler.cobrar();

    verify(gateway).criarCobrancaRenovacao(eq(RENOVACAO_ID), eq(1), eq(new BigDecimal("19.90")));
    verify(tentativaCobrancaRepository).save(tentativaCaptor.capture());
    assertThat(tentativaCaptor.getValue().getPaymentId())
        .as("payment id persistido")
        .isEqualTo("pay-123");
  }

  @Test
  @DisplayName("Nao deve preencher o payment id quando o gateway lanca excecao")
  void naoDevePreencherPaymentIdQuandoGatewayLancaExcecao() {
    // Fixture capturada para asseverar estado pos-falha.
    TentativaCobranca tentativa = tentativaProntaSemPaymentId();
    when(tentativaCobrancaRepository.buscarProntasParaCobrar()).thenReturn(List.of(tentativa));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    assertThat(tentativa.getPaymentId())
        .as("paymentId deve permanecer null quando o gateway falha")
        .isNull();
  }

  @Test
  @DisplayName("Deve contabilizar a falha tecnica e permanecer pendente abaixo do teto")
  void deveContabilizarFalhaTecnicaAbaixoDoTeto() {
    TentativaCobranca tentativa = tentativaProntaSemPaymentId();
    when(tentativaCobrancaRepository.buscarProntasParaCobrar()).thenReturn(List.of(tentativa));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    assertThat(tentativa.getFalhasTecnicas())
        .as("Contador de falhas tecnicas incrementado")
        .isEqualTo(1);
    assertThat(tentativa.getStatus())
        .as("Tentativa permanece pendente para o proximo ciclo")
        .isEqualTo(StatusTentativa.PENDENTE);
    verify(tentativaCobrancaRepository).save(tentativa);
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve esgotar a renovacao quando o teto de falhas tecnicas e atingido")
  void deveEsgotarQuandoTetoDeFalhasTecnicasAtingido() {
    TentativaCobranca tentativa = tentativaProntaSemPaymentId();
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();
    when(tentativaCobrancaRepository.buscarProntasParaCobrar()).thenReturn(List.of(tentativa));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    assertThat(tentativa.getFalhasTecnicas()).as("Terceira falha tecnica consecutiva").isEqualTo(3);
    assertThat(tentativa.getStatus())
        .as("Teto atingido esgota as tentativas da renovacao")
        .isEqualTo(StatusTentativa.TENTATIVAS_ESGOTADA);
    verify(tentativaCobrancaRepository).save(tentativa);
    verify(outboxRepository).save(outboxCaptor.capture());
    assertThat(outboxCaptor.getValue().getEventType())
        .as("Evento terminal publicado na outbox")
        .isEqualTo("RenovacaoTentativasEsgotadas");
    assertThat(outboxCaptor.getValue().getPayload())
        .as("Payload com o discriminador de esgotamento")
        .contains("\"tipo\":\"ESGOTADO\"")
        .contains("\"renovacaoId\":\"" + RENOVACAO_ID + "\"");
  }

  @Test
  @DisplayName("Deve zerar o contador quando o gateway se recupera antes do teto")
  void deveZerarContadorQuandoGatewaySeRecuperaAntesDoTeto() {
    TentativaCobranca tentativa = tentativaProntaSemPaymentId();
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();
    when(tentativaCobrancaRepository.buscarProntasParaCobrar()).thenReturn(List.of(tentativa));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-123"));

    scheduler.cobrar();

    assertThat(tentativa.getFalhasTecnicas())
        .as("Cobranca criada zera o contador antes do teto")
        .isZero();
    assertThat(tentativa.getStatus())
        .as("Ciclo prossegue com a cobranca criada")
        .isEqualTo(StatusTentativa.PENDENTE);
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Nao deve engolir bug de programacao como se fosse falha tecnica")
  void naoDeveEngolirBugDeProgramacaoComoFalhaTecnica() {
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new NullPointerException("mapeamento nulo"));

    assertThatThrownBy(() -> scheduler.cobrar())
        .as("bugs de programacao devem propagar, nao ser engolidos como falha tecnica")
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Nao deve envenenar o lote quando uma tentativa orfa e pulada")
  void naoDeveEnvenenarLoteQuandoTentativaOrfaPulada() {
    // Orfa PRIMEIRO: findByRenovacaoId vazio leva o scheduler a pular a tentativa; a proxima
    // tentativa valida precisa ainda assim ser cobrada e persistida.
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaOrfa(), tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ORFA_ID))
        .thenReturn(Optional.empty());
    when(pagamentoRenovacaoRepository.findByRenovacaoId(RENOVACAO_ID))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-123"));

    assertThatCode(() -> scheduler.cobrar())
        .as("lote nao deve falhar quando uma tentativa e orfa")
        .doesNotThrowAnyException();

    verify(tentativaCobrancaRepository).save(tentativaCaptor.capture());
    assertThat(tentativaCaptor.getValue().getRenovacaoId())
        .as("renovacao id da tentativa valida persistida")
        .isEqualTo(RENOVACAO_ID);
    assertThat(tentativaCaptor.getValue().getPaymentId())
        .as("payment id persistido da tentativa valida")
        .isEqualTo("pay-123");
  }

  @Test
  @DisplayName("Deve rejeitar a construcao com teto de falhas tecnicas menor que 1")
  void deveRejeitarConstrucaoComTetoMenorQueUm() {
    assertThatThrownBy(
            () ->
                new CobrancaRenovacaoScheduler(
                    tentativaCobrancaRepository,
                    pagamentoRenovacaoRepository,
                    gateway,
                    outboxRepository,
                    jsonMapper,
                    0))
        .as("Teto de falhas tecnicas invalido")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TentativaCobranca tentativaProntaSemPaymentId() {
    return tentativaDe(RENOVACAO_ID);
  }

  private TentativaCobranca tentativaOrfa() {
    return tentativaDe(RENOVACAO_ORFA_ID);
  }

  private TentativaCobranca tentativaDe(String renovacaoId) {
    return new PagamentoRenovacao(
            renovacaoId, ASSINATURA_ID, Plano.BASICO, new BigDecimal("19.90"), 2)
        .registrarTentativa();
  }

  private PagamentoRenovacao pagamentoComValor() {
    return new PagamentoRenovacao(
        RENOVACAO_ID, ASSINATURA_ID, Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
