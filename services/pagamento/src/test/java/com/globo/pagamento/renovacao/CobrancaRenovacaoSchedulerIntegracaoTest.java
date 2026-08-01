package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.globo.pagamento.cobranca.Plano;
import com.globo.pagamento.gateway.CobrancaCriada;
import com.globo.pagamento.gateway.GatewayPagamentoClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Teste de integracao do {@link CobrancaRenovacaoScheduler} contra o Postgres real.
 *
 * <p>Exercita o fluxo end-to-end do {@code cobrar()}: selecionar a tentativa pronta, carregar o
 * pagamento da renovacao, chamar o gateway (mockado) e persistir o {@code paymentId} devolvido. O
 * gateway e substituido por um mock Mockito para isolar a integracao de rede; o Kafka listener e o
 * admin sao desligados ({@code auto-startup=false}) para nao depender de broker. Usa o Postgres do
 * docker-compose (porta 5433, banco {@code pagamento}).
 *
 * <p>Este e um teste de regressao do caminho feliz. Ele nao exercita a concorrencia multi-instancia
 * (que depende da atomicidade do lock entre o {@code SELECT ... FOR UPDATE} e o {@code save}); essa
 * atomicidade e garantida pelo {@code @Transactional} no {@code cobrar()}, justificado por design e
 * nao coberta por este teste.
 */
@SpringBootTest
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
      "spring.kafka.listener.auto-startup=false",
      "spring.kafka.admin.auto-startup=false",
    })
class CobrancaRenovacaoSchedulerIntegracaoTest {

  @Autowired private CobrancaRenovacaoScheduler scheduler;
  @Autowired private TentativaCobrancaRepository tentativaCobrancaRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private GatewayPagamentoClient gateway;

  @BeforeEach
  void preparar() {
    when(gateway.criarCobrancaRenovacao(any(), anyInt(), any()))
        .thenReturn(new CobrancaCriada("pay-IT"));
    jdbcTemplate.update(
        "INSERT INTO pagamento_renovacao (renovacao_id, assinatura_id, plano, valor,"
            + " ciclo_referencia) VALUES (?, ?, ?, ?, ?)",
        "renov-1",
        "assinatura-1",
        Plano.BASICO.name(),
        new BigDecimal("19.90"),
        2);
    jdbcTemplate.update(
        "INSERT INTO tentativa_cobranca (renovacao_id, numero, status) VALUES (?, ?, ?)",
        "renov-1",
        1,
        StatusTentativa.PENDENTE.name());
  }

  @Test
  @DisplayName("Deve persistir o payment id ao cobrar a tentativa pronta")
  void devePersistirPaymentIdAoCobrarTentativaPronta() {
    scheduler.cobrar();

    TentativaCobranca tentativa = tentativaCobrancaRepository.findAll().getFirst();
    assertThat(tentativa.getPaymentId())
        .as("payment id persistido apos cobranca")
        .isEqualTo("pay-IT");
  }

  @AfterEach
  void limparTabelas() {
    jdbcTemplate.update("DELETE FROM tentativa_cobranca");
    jdbcTemplate.update("DELETE FROM pagamento_renovacao");
  }
}
