package com.globo.pagamento.renovacao.agendador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.CobrancaGatewayIndisponivelException;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link CobrancaRenovacaoScheduler}.
 *
 * <p>Verifica que o scheduler cobra as tentativas prontas e persiste o {@code paymentId} devolvido
 * pelo gateway na tentativa correspondente.
 */
@ExtendWith(MockitoExtension.class)
class CobrancaRenovacaoSchedulerTest {

  @Mock private TentativaCobrancaRepository tentativaCobrancaRepository;
  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  @Mock private GatewayPagamentoClient gateway;
  @InjectMocks private CobrancaRenovacaoScheduler scheduler;
  @Captor private ArgumentCaptor<TentativaCobranca> tentativaCaptor;

  @Test
  @DisplayName("Deve persistir a tentativa com o payment id devolvido")
  void devePersistirTentativaComPaymentId() {
    // Fixture "pronta": tentativa PENDENTE sem paymentId, elegivel para cobranca.
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId("renov-1"))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-123"));

    scheduler.cobrar();

    verify(gateway).criarCobrancaRenovacao(eq("renov-1"), eq(1), eq(new BigDecimal("19.90")));
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
    when(pagamentoRenovacaoRepository.findByRenovacaoId("renov-1"))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new CobrancaGatewayIndisponivelException());

    scheduler.cobrar();

    assertThat(tentativa.getPaymentId())
        .as("paymentId deve permanecer null quando o gateway falha")
        .isNull();
  }

  @Test
  @DisplayName("Nao deve engolir bug de programacao como se fosse falha tecnica")
  void naoDeveEngolirBugDeProgramacaoComoFalhaTecnica() {
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId("renov-1"))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenThrow(new NullPointerException("mapeamento nulo"));

    assertThatThrownBy(() -> scheduler.cobrar())
        .as("bugs de programacao devem propagar, nao ser engolidos como falha tecnica")
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("Nao deve envenenar o lote quando uma tentativa orfa levanta NoSuchElementException")
  void naoDeveEnvenenarLoteQuandoTentativaOrfaLevantaNoSuchElementException() {
    // Orfa PRIMEIRO: findByRenovacaoId vazio dispara o orElseThrow atual; a proxima
    // tentativa valida precisa ainda assim ser cobrada e persistida.
    when(tentativaCobrancaRepository.buscarProntasParaCobrar())
        .thenReturn(List.of(tentativaOrfa(), tentativaProntaSemPaymentId()));
    when(pagamentoRenovacaoRepository.findByRenovacaoId("renov-orfa")).thenReturn(Optional.empty());
    when(pagamentoRenovacaoRepository.findByRenovacaoId("renov-1"))
        .thenReturn(Optional.of(pagamentoComValor()));
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-123"));

    assertThatCode(() -> scheduler.cobrar())
        .as("lote nao deve falhar quando uma tentativa e orfa")
        .doesNotThrowAnyException();

    verify(tentativaCobrancaRepository).save(tentativaCaptor.capture());
    assertThat(tentativaCaptor.getValue().getRenovacaoId())
        .as("renovacao id da tentativa valida persistida")
        .isEqualTo("renov-1");
    assertThat(tentativaCaptor.getValue().getPaymentId())
        .as("payment id persistido da tentativa valida")
        .isEqualTo("pay-123");
  }

  private TentativaCobranca tentativaProntaSemPaymentId() {
    return tentativaDe("renov-1");
  }

  private TentativaCobranca tentativaOrfa() {
    return tentativaDe("renov-orfa");
  }

  private TentativaCobranca tentativaDe(String renovacaoId) {
    return new PagamentoRenovacao(
            renovacaoId, "assinatura-1", Plano.BASICO, new BigDecimal("19.90"), 2)
        .registrarTentativa();
  }

  private PagamentoRenovacao pagamentoComValor() {
    return new PagamentoRenovacao(
        "renov-1", "assinatura-1", Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
