package com.globo.pagamento.cancelamento.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Teste de contrato da migration da deduplicacao dos eventos de cancelamento. */
class CancelamentoEventoProcessadoMigrationTest {

  @Test
  @DisplayName("Deve criar a tabela de eventos de cancelamento processados")
  void deveCriarTabelaDeEventosDeCancelamentoProcessados() throws IOException {
    String sql =
        new ClassPathResource("db/migration/V8__cria_tabela_cancelamento_evento_processado.sql")
            .getContentAsString(StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);

    assertThat(sql)
        .as("Tabela de deduplicacao de cancelamentos")
        .contains("create table cancelamento_evento_processado");
    assertThat(sql)
        .as("event_id uuid obrigatorio")
        .containsPattern("event_id\\s+uuid\\s+not\\s+null");
    assertThat(sql)
        .as("Unicidade de event_id")
        .containsPattern("(?s)(event_id\\s+uuid.*?unique|unique\\s*\\([^)]*event_id)");
    assertThat(sql)
        .as("assinatura_id uuid obrigatorio")
        .containsPattern("assinatura_id\\s+uuid\\s+not\\s+null");
    assertThat(sql)
        .as("processado_em com fuso horario obrigatorio")
        .containsPattern("processado_em\\s+timestamptz\\s+not\\s+null");
  }
}
