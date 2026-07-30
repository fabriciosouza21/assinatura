package com.globo.pagamento.cobranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.messaging.event.AssinaturaSolicitada;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link CriarCobrancaService}.
 *
 * <p>Verifica a criacao de cobranca no gateway e a persistencia da correlacao, alem da idempotencia
 * no redelivery (correlacao ja existente nao dispara nova chamada ao gateway).
 */
@ExtendWith(MockitoExtension.class)
class CriarCobrancaServiceTest {

  @Mock private CobrancaRepository cobrancaRepository;
  @Mock private GatewayPagamentoClient gatewayPagamentoClient;
  @InjectMocks private CriarCobrancaService service;
  @Captor private ArgumentCaptor<Cobranca> cobrancaCaptor;

  @Test
  @DisplayName("Deve criar cobranca e persistir correlacao quando nao existe")
  void deveCriarCobranca() {
    AssinaturaSolicitada evento = eventoValido();
    String assinaturaId = evento.assinaturaId().toString();
    when(cobrancaRepository.existsByAssinaturaUuid(assinaturaId)).thenReturn(false);
    when(gatewayPagamentoClient.criarCobranca(eq(assinaturaId), eq(evento.valor())))
        .thenReturn(new CobrancaCriada("pay_123", "PENDING"));

    service.processar(evento);

    verify(cobrancaRepository).save(cobrancaCaptor.capture());
    Cobranca persistida = cobrancaCaptor.getValue();
    assertThat(persistida.getAssinaturaUuid())
        .as("Correlacao pela assinatura")
        .isEqualTo(assinaturaId);
    assertThat(persistida.getPaymentId()).as("PaymentId do gateway").isEqualTo("pay_123");
    assertThat(persistida.getStatus())
        .as("Status inicial da cobranca")
        .isEqualTo(StatusCobranca.PENDING);
  }

  @Test
  @DisplayName("Nao deve chamar o gateway quando a correlacao ja existe")
  void naoDeveChamarGatewayQuandoCorrelacaoExiste() {
    AssinaturaSolicitada evento = eventoValido();
    when(cobrancaRepository.existsByAssinaturaUuid(evento.assinaturaId().toString()))
        .thenReturn(true);

    service.processar(evento);

    verify(gatewayPagamentoClient, never()).criarCobranca(any(), any());
    verify(cobrancaRepository, never()).save(any());
  }

  private AssinaturaSolicitada eventoValido() {
    return new AssinaturaSolicitada(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        Instant.parse("2026-07-30T12:00:00Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        UUID.fromString("00000000-0000-0000-0000-000000000021"),
        Plano.BASICO,
        new BigDecimal("19.90"));
  }
}
