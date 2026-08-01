package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
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
        .thenThrow(new RuntimeException("timeout"));

    scheduler.cobrar();

    assertThat(tentativa.getPaymentId())
        .as("paymentId deve permanecer null quando o gateway falha")
        .isNull();
  }

  private TentativaCobranca tentativaProntaSemPaymentId() {
    return new TentativaCobranca("renov-1", 1);
  }

  private PagamentoRenovacao pagamentoComValor() {
    return new PagamentoRenovacao(
        "renov-1", "assinatura-1", Plano.BASICO, new BigDecimal("19.90"), 2);
  }
}
