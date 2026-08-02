package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.shared.contrato.Plano;
import com.globo.pagamento.shared.contrato.RenovacaoSolicitada;
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
 * Teste unitario do {@link CriarPagamentoRenovacao}.
 *
 * <p>Verifica a criacao do pagamento de renovacao e da primeira tentativa PENDENTE, e a
 * idempotencia no redelivery: um {@code renovacaoId} ja existente nao cria nova tentativa.
 */
@ExtendWith(MockitoExtension.class)
class CriarPagamentoRenovacaoTest {

  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  @Mock private TentativaCobrancaRepository tentativaCobrancaRepository;
  @InjectMocks private CriarPagamentoRenovacao service;
  @Captor private ArgumentCaptor<TentativaCobranca> tentativaCaptor;

  @Test
  @DisplayName("Deve criar a primeira tentativa PENDENTE quando a renovacao e nova")
  void deveCriarPrimeiraTentativaPendenteQuandoRenovacaoEhNova() {
    RenovacaoSolicitada evento = eventoValido();
    when(pagamentoRenovacaoRepository.inserirSeNaoExistir(
            eq(evento.renovacaoId().toString()),
            eq(evento.assinaturaId().toString()),
            eq(evento.plano().name()),
            eq(evento.valor()),
            eq(evento.cicloReferencia())))
        .thenReturn(1);
    when(pagamentoRenovacaoRepository.findByRenovacaoId(evento.renovacaoId().toString()))
        .thenReturn(Optional.of(pagamentoDe(evento)));

    service.processar(evento);

    verify(tentativaCobrancaRepository).save(tentativaCaptor.capture());
    TentativaCobranca tentativa = tentativaCaptor.getValue();
    assertThat(tentativa.getRenovacaoId())
        .as("Tentativa vinculada a renovacao")
        .isEqualTo(evento.renovacaoId().toString());
    assertThat(tentativa.getNumero()).as("Primeira tentativa").isEqualTo(1);
    assertThat(tentativa.getStatus())
        .as("Status inicial da tentativa")
        .isEqualTo(StatusTentativa.PENDENTE);
    assertThat(tentativa.getPaymentId()).as("Nenhuma chamada ao gateway na criacao").isNull();
  }

  @Test
  @DisplayName("Nao deve criar tentativa quando o pagamento da renovacao ja existe")
  void naoDeveCriarTentativaQuandoPagamentoJaExiste() {
    RenovacaoSolicitada evento = eventoValido();
    when(pagamentoRenovacaoRepository.inserirSeNaoExistir(
            anyString(), anyString(), any(), any(), anyInt()))
        .thenReturn(0);

    service.processar(evento);

    verify(tentativaCobrancaRepository, never()).save(any());
  }

  private PagamentoRenovacao pagamentoDe(RenovacaoSolicitada evento) {
    return new PagamentoRenovacao(
        evento.renovacaoId().toString(),
        evento.assinaturaId().toString(),
        com.globo.pagamento.cobranca.Plano.valueOf(evento.plano().name()),
        evento.valor(),
        evento.cicloReferencia());
  }

  private RenovacaoSolicitada eventoValido() {
    return new RenovacaoSolicitada(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        Instant.parse("2026-07-31T12:00:00Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000031"),
        UUID.fromString("00000000-0000-0000-0000-000000000011"),
        Plano.BASICO,
        new BigDecimal("19.90"),
        2);
  }
}
