package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import com.globo.pagamento.shared.contrato.StatusPagamento;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
 * Teste unitario do command {@link ProcessarWebhookRenovacao}.
 *
 * <p>Cobre as quatro decisoes possiveis sobre uma tentativa de cobranca de renovacao: aprovacao,
 * recusa com retry agendado, recusa que esgota o ciclo e status ainda pendente no gateway.
 */
@ExtendWith(MockitoExtension.class)
class ProcessarWebhookRenovacaoTest {

  private static final String RENOVACAO_ID = "00000000-0000-0000-0000-000000000031";
  private static final String ASSINATURA_ID = "00000000-0000-0000-0000-000000000011";
  private static final String PAYMENT_ID = "pay-123";
  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
  private static final List<Integer> BACKOFF_DIAS = List.of(1, 3);

  private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

  @Mock private TentativaCobrancaRepository tentativaRepository;
  @Mock private OutboxRepository outboxRepository;
  @Captor private ArgumentCaptor<TentativaCobranca> tentativaCaptor;
  @Captor private ArgumentCaptor<OutboxEvent> outboxCaptor;

  private ProcessarWebhookRenovacao command;

  @BeforeEach
  void setUp() {
    command =
        new ProcessarWebhookRenovacao(
            tentativaRepository, jsonMapper, outboxRepository, BACKOFF_DIAS);
  }

  @Test
  @DisplayName("Deve aprovar a tentativa e gravar o resultado aprovado na outbox")
  void deveGravarResultadoAprovadoQuandoGatewayAprova() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.APPROVED);

    assertThat(tentativa.getStatus())
        .as("Tentativa aprovada encerra o ciclo")
        .isEqualTo(StatusTentativa.APROVADA);
    verify(tentativaRepository).save(tentativaCaptor.capture());
    assertThat(tentativaCaptor.getAllValues())
        .as("Aprovacao nao cria nova tentativa")
        .containsExactly(tentativa);
    OutboxEvent evento = gravarOutbox();
    assertThat(evento.getEventId())
        .as("Evento da outbox carrega o eventId do webhook para dedup do consumidor")
        .isEqualTo(EVENT_ID);
    assertThat(evento.getAggregateType()).as("Tipo do agregado").isEqualTo("Renovacao");
    assertThat(evento.getAggregateId())
        .as("Key do Kafka preservada: assinaturaId")
        .isEqualTo(UUID.fromString(ASSINATURA_ID));
    assertThat(evento.getEventType())
        .as("Evento de aprovacao")
        .isEqualTo("PagamentoRenovacaoAprovado");
    assertThat(evento.getPayload())
        .as("Payload com renovacao, pagamento e ciclo")
        .contains("\"renovacaoId\":\"" + RENOVACAO_ID + "\"")
        .contains("\"paymentId\":\"" + PAYMENT_ID + "\"")
        .contains("\"cicloReferencia\":2");
  }

  @Test
  @DisplayName("Deve recusar a tentativa 1 e agendar a tentativa 2 sem gravar resultado")
  void deveAgendarSegundaTentativaQuandoPrimeiraRecusada() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));
    final Instant antes = Instant.now();

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.REJECTED);

    assertThat(tentativa.getStatus())
        .as("Tentativa recusada da lugar a proxima")
        .isEqualTo(StatusTentativa.RECUSADA);
    verify(tentativaRepository, org.mockito.Mockito.times(2)).save(tentativaCaptor.capture());
    TentativaCobranca proxima = tentativaCaptor.getAllValues().get(1);
    assertThat(proxima.getNumero()).as("Numero da tentativa de retry").isEqualTo(2);
    assertThat(proxima.getStatus())
        .as("Tentativa de retry nasce pendente")
        .isEqualTo(StatusTentativa.PENDENTE);
    assertThat(proxima.getPaymentId()).as("Tentativa de retry ainda nao foi cobrada").isNull();
    assertThat(proxima.getProximaTentativaEm())
        .as("Retry agendado um dia a frente, conforme o backoff")
        .isBetween(antes.plus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve recusar a tentativa 2 e agendar a tentativa 3 tres dias a frente")
  void deveAgendarTerceiraTentativaEmTresDiasQuandoSegundaRecusada() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 2);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));
    final Instant antes = Instant.now();

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.REJECTED);

    verify(tentativaRepository, org.mockito.Mockito.times(2)).save(tentativaCaptor.capture());
    TentativaCobranca proxima = tentativaCaptor.getAllValues().get(1);
    assertThat(proxima.getNumero()).as("Numero da ultima tentativa").isEqualTo(3);
    assertThat(proxima.getProximaTentativaEm())
        .as("Segunda janela do backoff")
        .isBetween(antes.plus(3, ChronoUnit.DAYS), Instant.now().plus(3, ChronoUnit.DAYS));
  }

  @Test
  @DisplayName("Deve esgotar o ciclo e gravar o terminal na outbox na recusa da ultima tentativa")
  void deveGravarTerminalQuandoUltimaTentativaRecusada() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 3);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.REJECTED);

    assertThat(tentativa.getStatus())
        .as("Ultima recusa esgota as tentativas")
        .isEqualTo(StatusTentativa.TENTATIVAS_ESGOTADA);
    verify(tentativaRepository).save(tentativaCaptor.capture());
    assertThat(tentativaCaptor.getAllValues())
        .as("Esgotamento nao cria nova tentativa")
        .containsExactly(tentativa);
    OutboxEvent evento = gravarOutbox();
    assertThat(evento.getEventType())
        .as("Evento terminal")
        .isEqualTo("RenovacaoTentativasEsgotadas");
    assertThat(evento.getPayload())
        .as("Evento terminal sem paymentId, conforme o contrato")
        .contains("\"renovacaoId\":\"" + RENOVACAO_ID + "\"")
        .doesNotContain("paymentId");
  }

  @Test
  @DisplayName("Nao deve consumir a tentativa quando o gateway ainda reporta pendente")
  void naoDeveConsumirTentativaQuandoGatewayPendente() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.PENDING);

    assertThat(tentativa.getStatus())
        .as("Tentativa segue aguardando decisao")
        .isEqualTo(StatusTentativa.PENDENTE);
    verify(tentativaRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Nao deve decidir de novo uma tentativa ja decidida")
  void naoDeveDecidirDeNovoTentativaJaDecidida() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    tentativa.aprovar();
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.APPROVED);

    verify(tentativaRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve rejeitar a decisao quando a tentativa pertence a outra renovacao")
  void deveRejeitarDecisaoQuandoTentativaDeOutraRenovacao() {
    PagamentoRenovacao pagamento = pagamento();
    PagamentoRenovacao outroPagamento =
        new PagamentoRenovacao(
            "00000000-0000-0000-0000-000000000032",
            ASSINATURA_ID,
            Plano.BASICO,
            new BigDecimal("19.90"),
            2);
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    assertThatThrownBy(
            () -> command.decidir(outroPagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.APPROVED))
        .as("Decisao cruzada entre renovacoes distintas rejeitada")
        .isInstanceOf(DecisaoRenovacaoIndisponivelException.class);
    verify(tentativaRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve bloquear a tentativa para escrita ao decidir, serializando reenvios")
  void deveBuscarTentativaComLockPessimista() {
    PagamentoRenovacao pagamento = pagamento();
    TentativaCobranca tentativa = tentativaCobrada(pagamento, 1);
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.of(tentativa));

    command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.APPROVED);

    verify(tentativaRepository).buscarPorPaymentIdParaAtualizacao(PAYMENT_ID);
  }

  @Test
  @DisplayName("Deve pedir reenvio quando nenhuma tentativa foi cobrada sob o payment id")
  void devePedirReenvioQuandoTentativaNaoEncontrada() {
    PagamentoRenovacao pagamento = pagamento();
    when(tentativaRepository.buscarPorPaymentIdParaAtualizacao(PAYMENT_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> command.decidir(pagamento, EVENT_ID, PAYMENT_ID, StatusPagamento.APPROVED))
        .as("Absorver a notificacao perderia a decisao: o gateway precisa reenviar")
        .isInstanceOf(DecisaoRenovacaoIndisponivelException.class);
    verify(tentativaRepository, never()).save(any());
    verify(outboxRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve rejeitar a construcao quando a janela de backoff esta vazia")
  void deveRejeitarConstrucaoComBackoffVazio() {
    assertThatThrownBy(
            () ->
                new ProcessarWebhookRenovacao(
                    tentativaRepository, jsonMapper, outboxRepository, List.of()))
        .as("Sem janela de backoff nao ha ciclo de tentativas")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve rejeitar a construcao quando a janela de backoff e nula")
  void deveRejeitarConstrucaoComBackoffNulo() {
    assertThatThrownBy(
            () ->
                new ProcessarWebhookRenovacao(
                    tentativaRepository, jsonMapper, outboxRepository, null))
        .as("Janela de backoff nula rejeitada")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve rejeitar a construcao quando a janela de backoff tem valor negativo")
  void deveRejeitarConstrucaoComBackoffNegativo() {
    assertThatThrownBy(
            () ->
                new ProcessarWebhookRenovacao(
                    tentativaRepository, jsonMapper, outboxRepository, List.of(-1)))
        .as("Janela de backoff negativa rejeitada")
        .isInstanceOf(IllegalArgumentException.class);
  }

  private OutboxEvent gravarOutbox() {
    verify(outboxRepository).save(outboxCaptor.capture());
    return outboxCaptor.getValue();
  }

  private PagamentoRenovacao pagamento() {
    return new PagamentoRenovacao(
        RENOVACAO_ID, ASSINATURA_ID, Plano.BASICO, new BigDecimal("19.90"), 2);
  }

  private TentativaCobranca tentativaCobrada(PagamentoRenovacao pagamento, int numero) {
    TentativaCobranca tentativa = pagamento.registrarTentativa();
    for (int atual = 1; atual < numero; atual++) {
      TentativaCobranca recusada = tentativa;
      recusada.recusar();
      tentativa = pagamento.registrarTentativa(recusada, Instant.now());
    }
    tentativa.registrarCobranca(PAYMENT_ID);
    return tentativa;
  }
}
