package com.globo.assinatura.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publica periodicamente o numero de eventos da outbox por status como metricas do Micrometer,
 * tornando o acumulo de falhas visivel em dashboard sem depender de log ou SQL.
 *
 * <p>Registra no arranque um gauge {@code outbox.eventos} para cada status nao terminal do ciclo de
 * publicacao ({@code PENDENTE}, {@code RETENTATIVA_DLQ} e {@code FALHA}), com tags {@code servico}
 * e {@code status}, e o atualiza periodicamente com a contagem do banco.
 *
 * <p>Mantenha em paridade com o {@code OutboxMetricasScheduler} de services/pagamento.
 */
@Component
public class OutboxMetricasScheduler {

  private static final Logger log = LoggerFactory.getLogger(OutboxMetricasScheduler.class);

  private static final String METRICA_NOME = "outbox.eventos";
  private static final List<OutboxStatus> STATUS_MONITORADOS =
      List.of(OutboxStatus.PENDENTE, OutboxStatus.RETENTATIVA_DLQ, OutboxStatus.FALHA);

  private final OutboxRepository outboxRepository;
  private final Map<OutboxStatus, AtomicLong> contagens = new EnumMap<>(OutboxStatus.class);

  /**
   * Constroi o scheduler registrando os gauges de contagem por status no registry.
   *
   * <p>Os gauges nascem com valor zero, entao o monitoramento ja exibe o estado corrente (ou a
   * ausencia de eventos) antes da primeira atualizacao periodica.
   *
   * @param outboxRepository repositorio da outbox
   * @param meterRegistry registry do Micrometer onde os gauges sao registrados
   * @param servico nome do servico, usado como tag para distinguir as instancias no dashboard
   */
  public OutboxMetricasScheduler(
      OutboxRepository outboxRepository,
      MeterRegistry meterRegistry,
      @Value("${spring.application.name}") String servico) {
    this.outboxRepository = outboxRepository;
    for (OutboxStatus status : STATUS_MONITORADOS) {
      AtomicLong valor = new AtomicLong();
      Gauge.builder(METRICA_NOME, valor, AtomicLong::get)
          .tag("servico", servico)
          .tag("status", status.name().toLowerCase())
          .register(meterRegistry);
      contagens.put(status, valor);
    }
  }

  /**
   * Atualiza os gauges com a contagem corrente de eventos por status na outbox.
   *
   * <p>Conta apenas os status monitorados; statuses fora do monitoramento (como {@code PUBLICADO})
   * nao produzem gauge.
   */
  @Scheduled(fixedDelayString = "${app.outbox.metricas.intervalo-ms}")
  public void atualizarMetricas() {
    Map<OutboxStatus, Long> contadas = new EnumMap<>(OutboxStatus.class);
    for (Object[] linha : outboxRepository.contarPorStatus()) {
      contadas.put(OutboxStatus.valueOf((String) linha[0]), ((Number) linha[1]).longValue());
    }
    for (OutboxStatus status : STATUS_MONITORADOS) {
      contagens.get(status).set(contadas.getOrDefault(status, 0L));
    }
    log.atDebug()
        .addKeyValue("event", "outbox_metricas_atualizadas")
        .addKeyValue("pendente", contagens.get(OutboxStatus.PENDENTE).get())
        .addKeyValue("retentativaDlq", contagens.get(OutboxStatus.RETENTATIVA_DLQ).get())
        .addKeyValue("falha", contagens.get(OutboxStatus.FALHA).get())
        .log("Metricas da outbox atualizadas");
  }
}
