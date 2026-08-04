package com.globo.pagamento.cancelamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.PagamentoRenovacaoRepository;
import com.globo.pagamento.renovacao.StatusTentativa;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CancelarTentativasPendentesTest {

  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;

  @Mock private TentativaCobrancaRepository tentativaCobrancaRepository;

  private CancelarTentativasPendentes command;

  @BeforeEach
  void setUp() {
    command =
        new CancelarTentativasPendentes(pagamentoRenovacaoRepository, tentativaCobrancaRepository);
  }

  @Test
  @DisplayName("Deve cancelar tentativas pendentes das renovacoes da assinatura")
  void deveCancelarTentativasPendentesDasRenovacoesDaAssinatura() {
    PagamentoRenovacao renovacao =
        new PagamentoRenovacao("renov-1", "assinatura-1", Plano.BASICO, new BigDecimal("19.90"), 1);
    TentativaCobranca tentativa = renovacao.registrarTentativa();
    when(pagamentoRenovacaoRepository.findByAssinaturaId("assinatura-1"))
        .thenReturn(List.of(renovacao));
    when(tentativaCobrancaRepository.buscarPendentesPorRenovacaoId("renov-1"))
        .thenReturn(List.of(tentativa));

    command.executar("assinatura-1");

    assertThat(tentativa.getStatus())
        .as("Status da tentativa cancelada")
        .isEqualTo(StatusTentativa.CANCELADA);
    assertThat(tentativa.estaPendente()).as("Tentativa cancelada nao permanece pendente").isFalse();
    verify(tentativaCobrancaRepository).save(tentativa);
  }
}
