package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Teste unitario puro de {@link TentativaCobranca}, sem contexto Spring. */
class TentativaCobrancaTest {

  @Test
  @DisplayName("Deve registrar o payment id informado")
  void deveRegistrarPaymentId() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    tentativa.registrarCobranca("pay-123");

    assertThat(tentativa.getPaymentId())
        .as("Payment id registrado apos chamada ao gateway")
        .isEqualTo("pay-123");
  }

  @Test
  @DisplayName("Deve recusar a criacao quando o numero da tentativa e menor que 1")
  void deveRecusarCriacaoComNumeroMenorQueUm() {
    assertThatThrownBy(() -> new TentativaCobranca("renov-uuid", 0))
        .as("Numero de tentativa fora da sequencia rejeitado na construcao")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve recusar a criacao quando a renovacao nao e informada")
  void deveRecusarCriacaoSemRenovacao() {
    assertThatThrownBy(() -> new TentativaCobranca(" ", 1))
        .as("Tentativa sem renovacao rejeitada na construcao")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve recusar uma segunda decisao sobre a mesma tentativa")
  void deveRecusarSegundaDecisao() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.aprovar();

    assertThatThrownBy(tentativa::recusar)
        .as("Tentativa ja decidida nao pode mudar de status")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Deve recusar o agendamento sem instante informado")
  void deveRecusarAgendamentoSemInstante() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    assertThatThrownBy(() -> tentativa.agendarPara(null))
        .as("Agendamento nulo rejeitado")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deve cancelar tentativa pendente")
  void deveCancelarTentativaPendente() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    tentativa.cancelar();

    assertThat(tentativa.getStatus())
        .as("Status da tentativa cancelada")
        .isEqualTo(StatusTentativa.CANCELADA);
    assertThat(tentativa.estaPendente()).as("Tentativa cancelada nao permanece pendente").isFalse();
  }
}
