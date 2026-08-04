package com.globo.pagamento.adesao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.gateway.GatewayPagamentoClient;
import com.globo.pagamento.shared.contrato.AssinaturaSolicitada;
import com.globo.pagamento.shared.contrato.Plano;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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
 * Teste unitario do {@link CriarCobrancaAdesao}.
 *
 * <p>Verifica a persistencia da tentativa de adesao em {@link StatusTentativaAdesao#PENDENTE} antes
 * de qualquer chamada ao gateway, e a idempotencia no redelivery (tentativa ja existente nao
 * persiste de novo).
 */
@ExtendWith(MockitoExtension.class)
class CriarCobrancaAdesaoTest {

  @Mock private CobrancaAdesaoTentativaRepository tentativaRepository;
  @Mock private GatewayPagamentoClient gatewayPagamentoClient;
  @InjectMocks private CriarCobrancaAdesao service;
  @Captor private ArgumentCaptor<CobrancaAdesaoTentativa> tentativaCaptor;

  @Test
  @DisplayName("Deve criar tentativa PENDENTE sem chamar o gateway")
  void deveCriarTentativaPendenteSemChamarGateway() {
    AssinaturaSolicitada evento = eventoValido();
    String assinaturaId = evento.assinaturaId().toString();
    when(tentativaRepository.findByAssinaturaUuid(assinaturaId)).thenReturn(Optional.empty());

    service.processar(evento);

    verify(tentativaRepository).save(tentativaCaptor.capture());
    CobrancaAdesaoTentativa persistida = tentativaCaptor.getValue();
    assertThat(persistida.getAssinaturaUuid())
        .as("Tentativa pela assinatura")
        .isEqualTo(assinaturaId);
    assertThat(persistida.getValor()).as("Valor da adesao").isEqualTo(evento.valor());
    assertThat(persistida.getStatus())
        .as("Status inicial da tentativa")
        .isEqualTo(StatusTentativaAdesao.PENDENTE);
    verify(gatewayPagamentoClient, never()).criarCobranca(any(), any());
  }

  @Test
  @DisplayName("Nao deve persistir quando a tentativa ja existe")
  void naoDevePersistirQuandoTentativaJaExiste() {
    AssinaturaSolicitada evento = eventoValido();
    when(tentativaRepository.findByAssinaturaUuid(evento.assinaturaId().toString()))
        .thenReturn(
            Optional.of(
                new CobrancaAdesaoTentativa(
                    evento.assinaturaId().toString(), new BigDecimal("19.90"))));

    service.processar(evento);

    verify(tentativaRepository, never()).save(any());
    verify(gatewayPagamentoClient, never()).criarCobranca(any(), any());
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
