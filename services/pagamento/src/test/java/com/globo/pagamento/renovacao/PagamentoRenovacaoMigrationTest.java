package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Teste de contrato da migration do indice de renovacoes por assinatura. */
class PagamentoRenovacaoMigrationTest {

  @Test
  @DisplayName("Deve indexar as renovacoes pela assinatura")
  void deveIndexarRenovacoesPelaAssinatura() throws IOException {
    String sql =
        new ClassPathResource("db/migration/V9__cria_indice_pagamento_renovacao_assinatura_id.sql")
            .getContentAsString(StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .as("Indice de assinatura nas renovacoes")
        .containsPattern("create\\s+index\\s+ix_pagamento_renovacao_assinatura_id");
    assertThat(sql)
        .as("Coluna indexada")
        .containsPattern("on\\s+pagamento_renovacao\\s*\\(assinatura_id\\)");
  }
}
