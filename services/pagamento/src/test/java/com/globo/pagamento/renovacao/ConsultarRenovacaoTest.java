package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.renovacao.api.RenovacaoResponse;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultarRenovacaoTest {

  @Mock private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;
  @Mock private TentativaCobrancaRepository tentativaCobrancaRepository;

  @InjectMocks private ConsultarRenovacao query;

  @Test
  @DisplayName("Deve consultar a renovacao mais recente retornando paymentId e status da tentativa")
  void deveConsultarRenovacaoExistenteRetornandoDadosDaTentativa() {
    PagamentoRenovacao pagamento =
        new PagamentoRenovacao(
            "renov-uuid", "assinatura-uuid", Plano.BASICO, new BigDecimal("19.90"), 2);
    TentativaCobranca tentativa = pagamento.registrarTentativa();
    tentativa.registrarCobranca("pay-123");
    when(pagamentoRenovacaoRepository.findFirstByAssinaturaIdOrderByCriadoEmDesc("assinatura-uuid"))
        .thenReturn(Optional.of(pagamento));
    when(tentativaCobrancaRepository.findFirstByRenovacaoIdOrderByNumeroDesc("renov-uuid"))
        .thenReturn(Optional.of(tentativa));

    RenovacaoResponse resposta = query.executar("assinatura-uuid");

    assertThat(resposta.renovacaoId()).as("Uuid da renovacao").isEqualTo("renov-uuid");
    assertThat(resposta.cicloReferencia()).as("Ciclo renovado").isEqualTo(2);
    assertThat(resposta.valor()).as("Valor da renovacao").isEqualByComparingTo("19.90");
    assertThat(resposta.numeroTentativa()).as("Numero da tentativa").isEqualTo(1);
    assertThat(resposta.statusTentativa())
        .as("Status da tentativa")
        .isEqualTo(StatusTentativa.PENDENTE);
    assertThat(resposta.paymentId()).as("Payment id da tentativa cobrada").isEqualTo("pay-123");
  }

  @Test
  @DisplayName("Deve lancar nao encontrado ao consultar assinatura que nunca renovou")
  void deveLancarNaoEncontradoAoConsultarAssinaturaQueNuncaRenovou() {
    when(pagamentoRenovacaoRepository.findFirstByAssinaturaIdOrderByCriadoEmDesc(
            "assinatura-inexistente"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> query.executar("assinatura-inexistente"))
        .as("Assinatura sem renovacao deve gerar nao encontrado")
        .isInstanceOf(RenovacaoNaoEncontradaException.class);
  }
}
