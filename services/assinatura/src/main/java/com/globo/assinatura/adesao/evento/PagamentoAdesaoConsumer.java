package com.globo.assinatura.adesao.evento;

import com.globo.assinatura.adesao.ConfirmarPagamentoAdesao;
import com.globo.assinatura.shared.contrato.PagamentoStatusAtualizado;
import com.globo.assinatura.shared.kafka.EventoInvalidoException;
import com.globo.assinatura.shared.kafka.MessagingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Kafka do topico de status de pagamento.
 *
 * <p>Desserializa o payload JSON em {@link PagamentoStatusAtualizado}, valida os campos
 * obrigatorios e delega o processamento de dominio ao command {@link ConfirmarPagamentoAdesao}.
 * Eventos invalidos disparam {@link EventoInvalidoException}, tratada como nao retentavel pelo
 * {@code DefaultErrorHandler} configurado em {@link MessagingConfig} (vai direto para a DLQ).
 */
@Component
public class PagamentoAdesaoConsumer {

  private static final Logger log = LoggerFactory.getLogger(PagamentoAdesaoConsumer.class);

  private final ConfirmarPagamentoAdesao processarPagamento;
  private final ObjectMapper objectMapper;

  /**
   * Constroi o consumer com o command e o mapeador JSON injetados.
   *
   * @param processarPagamento command de processamento de evento de pagamento
   * @param objectMapper mapeador JSON para desserializar o payload recebido
   */
  public PagamentoAdesaoConsumer(
      ConfirmarPagamentoAdesao processarPagamento, ObjectMapper objectMapper) {
    this.processarPagamento = processarPagamento;
    this.objectMapper = objectMapper;
  }

  /**
   * Consome uma mensagem do topico de status de pagamento.
   *
   * @param payload conteudo JSON da mensagem recebida
   */
  @KafkaListener(topics = "${app.kafka.topico-pagamento-status-atualizado}", groupId = "assinatura")
  public void consumir(String payload) {
    PagamentoStatusAtualizado evento = desserializar(payload);
    validar(evento);
    processarPagamento.executar(evento);
    log.atInfo()
        .addKeyValue("event", "pagamento_status_processado")
        .addKeyValue("assinaturaId", evento.assinaturaId())
        .addKeyValue("statusFinal", evento.status())
        .log("Status de pagamento processado");
  }

  private PagamentoStatusAtualizado desserializar(String payload) {
    try {
      return objectMapper.readValue(payload, PagamentoStatusAtualizado.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private void validar(PagamentoStatusAtualizado evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.status() == null) {
      throw new EventoInvalidoException("status ausente");
    }
  }
}
