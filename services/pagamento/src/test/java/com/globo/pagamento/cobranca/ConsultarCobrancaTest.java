package com.globo.pagamento.cobranca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultarCobrancaTest {

  @Mock private CobrancaRepository cobrancaRepository;

  @InjectMocks private ConsultarCobranca query;

  @Test
  @DisplayName("Deve consultar cobranca existente retornando paymentId e status")
  void deveConsultarCobrancaExistenteRetornandoDadosDoGateway() {
    Cobranca cobranca = new Cobranca("assinatura-uuid", "pay-123", StatusCobranca.APPROVED);
    when(cobrancaRepository.findByAssinaturaUuid("assinatura-uuid"))
        .thenReturn(Optional.of(cobranca));

    CobrancaResponse resposta = query.executar("assinatura-uuid");

    assertThat(resposta.paymentId()).as("Payment id da cobranca").isEqualTo("pay-123");
    assertThat(resposta.status()).as("Status da cobranca").isEqualTo(StatusCobranca.APPROVED);
  }

  @Test
  @DisplayName("Deve lancar nao encontrado ao consultar cobranca inexistente")
  void deveLancarNaoEncontradoAoConsultarCobrancaInexistente() {
    when(cobrancaRepository.findByAssinaturaUuid("assinatura-inexistente"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> query.executar("assinatura-inexistente"))
        .as("Cobranca inexistente deve gerar nao encontrado")
        .isInstanceOf(CobrancaNaoEncontradaException.class);
  }
}
