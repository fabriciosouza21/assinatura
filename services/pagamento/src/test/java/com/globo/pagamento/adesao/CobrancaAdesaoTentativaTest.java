package com.globo.pagamento.adesao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CobrancaAdesaoTentativaTest {

  @Test
  @DisplayName("Deve nascer PENDENTE sem paymentId e sem proximaTentativaEm")
  void deveNascerPendenteSemPaymentId() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));

    assertThat(tentativa.getStatus())
        .as("Status inicial")
        .isEqualTo(StatusTentativaAdesao.PENDENTE);
    assertThat(tentativa.getPaymentId()).as("PaymentId inicial").isNull();
    assertThat(tentativa.getProximaTentativaEm()).as("Proxima tentativa inicial").isNull();
    assertThat(tentativa.getFalhasTecnicas()).as("Falhas tecnicas iniciais").isZero();
  }

  @Test
  @DisplayName("Deve contabilizar uma falha tecnica mantendo status PENDENTE")
  void deveContabilizarFalhaTecnica() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));

    tentativa.registrarFalhaTecnica();

    assertThat(tentativa.getFalhasTecnicas()).as("Falhas tecnicas apos uma falha").isEqualTo(1);
    assertThat(tentativa.getStatus())
        .as("Status mantido apos falha tecnica")
        .isEqualTo(StatusTentativaAdesao.PENDENTE);
  }

  @Test
  @DisplayName("Deve atingir o teto de falhas tecnicas apos N falhas")
  void deveAtingirTetoDeFalhasTecnicas() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();

    assertThat(tentativa.esgotouFalhasTecnicas(3)).as("Esgota no teto de 3").isTrue();
    assertThat(tentativa.esgotouFalhasTecnicas(5))
        .as("Nao esgota acima do teto informado")
        .isFalse();
  }

  @Test
  @DisplayName("Deve transitar para ESGOTADA ao esgotar")
  void deveTransitarParaEsgotada() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));

    tentativa.esgotar();

    assertThat(tentativa.getStatus())
        .as("Status apos esgotar")
        .isEqualTo(StatusTentativaAdesao.ESGOTADA);
  }

  @Test
  @DisplayName("Deve registrar cobranca zerando falhas e preenchendo paymentId")
  void deveRegistrarCobrancaComSucesso() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));
    tentativa.registrarFalhaTecnica();
    tentativa.registrarFalhaTecnica();

    tentativa.registrarCobranca("pay-123");

    assertThat(tentativa.getPaymentId()).as("PaymentId apos cobranca").isEqualTo("pay-123");
    assertThat(tentativa.getFalhasTecnicas()).as("Falhas tecnicas zeradas apos cobranca").isZero();
    assertThat(tentativa.getStatus())
        .as("Status apos cobranca")
        .isEqualTo(StatusTentativaAdesao.COBRADA);
  }

  @Test
  @DisplayName("Deve rejeitar transicao em tentativa ja decidida")
  void deveRejeitarTransicaoEmTentativaDecidida() {
    CobrancaAdesaoTentativa tentativa =
        new CobrancaAdesaoTentativa("uuid-1", new BigDecimal("99.90"));
    tentativa.esgotar();

    assertThatThrownBy(tentativa::registrarFalhaTecnica)
        .as("Registrar falha apos esgotar")
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tentativa.registrarCobranca("pay-1"))
        .as("Registrar cobranca apos esgotar")
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(tentativa::esgotar)
        .as("Esgotar apos esgotar")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Deve rejeitar construcao com assinaturaUuid vazio ou valor invalido")
  void deveRejeitarConstrucaoInvalida() {
    assertThatThrownBy(() -> new CobrancaAdesaoTentativa("", new BigDecimal("99.90")))
        .as("AssinaturaUuid vazio")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CobrancaAdesaoTentativa("uuid-1", BigDecimal.ZERO))
        .as("Valor zero")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CobrancaAdesaoTentativa("uuid-1", null))
        .as("Valor nulo")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
