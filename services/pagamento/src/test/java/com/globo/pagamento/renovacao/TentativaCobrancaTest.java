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
  @DisplayName("Deve registrar falha tecnica incrementando o contador")
  void deveRegistrarFalhaTecnica() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();

    assertThat(tentativa.getFalhasTecnicas())
        .as("Contador de falhas tecnicas consecutivas")
        .isEqualTo(2);
    assertThat(tentativa.getStatus())
        .as("Falha tecnica nao decide a tentativa")
        .isEqualTo(StatusTentativa.PENDENTE);
  }

  @Test
  @DisplayName("Deve esgotar as falhas tecnicas quando o contador alcanca o teto")
  void deveEsgotarFalhasTecnicasQuandoContadorAlcancaTeto() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);

    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();

    assertThat(tentativa.esgotouFalhasTecnicas(3))
        .as("Teto alcancado com tres falhas tecnicas consecutivas")
        .isTrue();
    assertThat(tentativa.esgotouFalhasTecnicas(4))
        .as("Teto maior que o contador nao esgota")
        .isFalse();
  }

  @Test
  @DisplayName("Deve esgotar as falhas tecnicas quando o teto e igual a um")
  void deveEsgotarComTetoUm() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.registrarFalhaTecnica();

    assertThat(tentativa.esgotouFalhasTecnicas(1))
        .as("Primeira falha tecnica esgota com teto minimo")
        .isTrue();
  }

  @Test
  @DisplayName("Deve esgotar as falhas tecnicas quando o contador ultrapassa o teto")
  void deveEsgotarQuandoContadorUltrapassaTeto() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    for (int i = 0; i < 4; i++) {
      tentativa.registrarFalhaTecnica();
    }

    assertThat(tentativa.esgotouFalhasTecnicas(3))
        .as("Contador acima do teto ainda esgota as falhas tecnicas")
        .isTrue();
  }

  @Test
  @DisplayName("Deve zerar o contador de falhas tecnicas quando a cobranca e registrada")
  void deveZerarContadorDeFalhasTecnicasAoRegistrarCobranca() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();

    tentativa.registrarCobranca("pay-123");

    assertThat(tentativa.getFalhasTecnicas())
        .as("Gateway recuperado zera o contador antes do teto")
        .isZero();
  }

  @Test
  @DisplayName("Deve recusar registrar falha tecnica em tentativa ja decidida")
  void deveRecusarRegistrarFalhaTecnicaEmTentativaJaDecidida() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.recusar();

    assertThatThrownBy(tentativa::registrarFalhaTecnica)
        .as("Tentativa ja decidida nao pode acumular falha tecnica")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Deve recusar registrar cobranca em tentativa ja decidida")
  void deveRecusarRegistrarCobrancaEmTentativaJaDecidida() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.esgotar();

    assertThatThrownBy(() -> tentativa.registrarCobranca("pay-1"))
        .as("Tentativa ja decidida nao pode receber nova cobranca")
        .isInstanceOf(IllegalStateException.class);
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

  @Test
  @DisplayName("Deve recusar o cancelamento de tentativa ja decidida")
  void deveRecusarCancelamentoDeTentativaJaDecidida() {
    TentativaCobranca tentativa = new TentativaCobranca("renov-uuid", 1);
    tentativa.recusar();

    assertThatThrownBy(tentativa::cancelar)
        .as("Tentativa ja decidida nao pode ser cancelada")
        .isInstanceOf(IllegalStateException.class);
  }
}
