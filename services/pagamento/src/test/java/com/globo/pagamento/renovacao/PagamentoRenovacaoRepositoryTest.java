package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.pagamento.cobranca.Plano;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;

/**
 * Teste de integracao do {@link PagamentoRenovacaoRepository} contra o Postgres real.
 *
 * <p>Garante que a migracao V4 cria a tabela {@code pagamento_renovacao} e que a idempotencia por
 * {@code renovacaoId} de fato ocorre no banco: o {@code INSERT ... ON CONFLICT (renovacao_id) DO
 * NOTHING} retorna {@code 1} na primeira insercao e {@code 0} no redelivery, sem duplicar a linha.
 * Exercita tambem o binding do enum {@link Plano} para {@code VARCHAR(32)} na query nativa. Usa o
 * Postgres do docker-compose (porta 5433, banco {@code pagamento}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class PagamentoRenovacaoRepositoryTest {

  @Autowired private PagamentoRenovacaoRepository pagamentoRenovacaoRepository;

  @Test
  @DisplayName("Deve inserir na primeira vez e persistir o plano como string")
  void deveInserirPlanoNaPrimeiraVez() {
    int inseridas =
        pagamentoRenovacaoRepository.inserirSeNaoExistir(
            "renovacao-uuid-1",
            "assinatura-uuid-1",
            Plano.BASICO.name(),
            new BigDecimal("19.90"),
            2);
    pagamentoRenovacaoRepository.flush();

    assertThat(inseridas).as("Primeira insercao insere uma linha").isEqualTo(1);
    List<PagamentoRenovacao> registros = pagamentoRenovacaoRepository.findAll();
    assertThat(registros).as("Linha do pagamento de renovacao persistida").hasSize(1);
    PagamentoRenovacao registro = registros.getFirst();
    assertThat(registro.getRenovacaoId())
        .as("renovacaoId persistido")
        .isEqualTo("renovacao-uuid-1");
    assertThat(registro.getPlano())
        .as("Plano persistido como enum desserializado de string")
        .isEqualTo(Plano.BASICO);
    assertThat(registro.getValor())
        .as("Valor em reais persistido")
        .isEqualByComparingTo(new BigDecimal("19.90"));
    assertThat(registro.getCicloReferencia()).as("Ciclo de referencia persistido").isEqualTo(2);
    assertThat(registro.getCriadoEm()).as("CriadoEm preenchido pelo banco").isNotNull();
  }

  @Test
  @DisplayName("Nao deve duplicar nem inserir quando o renovacaoId ja existe")
  void naoDeveDuplicarQuandoRenovacaoIdJaExiste() {
    pagamentoRenovacaoRepository.inserirSeNaoExistir(
        "renovacao-uuid-2", "assinatura-uuid-2", Plano.PREMIUM.name(), new BigDecimal("49.90"), 3);
    pagamentoRenovacaoRepository.flush();

    int redelivery =
        pagamentoRenovacaoRepository.inserirSeNaoExistir(
            "renovacao-uuid-2",
            "assinatura-uuid-alterada",
            Plano.BASICO.name(),
            new BigDecimal("19.90"),
            2);
    pagamentoRenovacaoRepository.flush();

    assertThat(redelivery)
        .as("Redelivery descartado pelo ON CONFLICT DO NOTHING retorna zero linhas")
        .isEqualTo(0);
    List<PagamentoRenovacao> registros = pagamentoRenovacaoRepository.findAll();
    assertThat(registros).as("Redelivery nao duplica a linha").hasSize(1);
    PagamentoRenovacao registro = registros.getFirst();
    assertThat(registro.getAssinaturaId())
        .as("Original preservada: redelivery nao sobrescreve")
        .isEqualTo("assinatura-uuid-2");
    assertThat(registro.getPlano())
        .as("Original preservada: plano nao sobrescrito pelo redelivery")
        .isEqualTo(Plano.PREMIUM);
  }
}
