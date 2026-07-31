package com.globo.assinatura.messaging;

import com.globo.assinatura.assinatura.ProcessarPagamento;
import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Kafka do topico de status de pagamento.
 *
 * <p>Responsavel apenas por desserializar o payload JSON em {@link PagamentoStatusAtualizado} e
 * delegar o processamento de dominio ao command {@link ProcessarPagamento}. Erros de
 * desserializacao (subtipos de {@code tools.jackson.core.JacksonException}, unchecked) sao
 * tratatados como nao recuperaveis pelo {@code DefaultErrorHandler} configurado em {@link
 * MessagingConfig}, enviando a mensagem direto para a DLQ.
 */
@Component
public class PagamentoStatusConsumer {

  private final ProcessarPagamento processarPagamento;
  private final ObjectMapper objectMapper;

  /**
   * Constroi o consumer com o command e o mapeador JSON injetados.
   *
   * @param processarPagamento command de processamento de evento de pagamento
   * @param objectMapper mapeador JSON para desserializar o payload recebido
   */
  public PagamentoStatusConsumer(ProcessarPagamento processarPagamento, ObjectMapper objectMapper) {
    this.processarPagamento = processarPagamento;
    this.objectMapper = objectMapper;
  }

  /**
   * Consome uma mensagem do topico de status de pagamento.
   *
   * <p>Desserializa o payload JSON e delega ao command de processamento. Falhas de desserializacao
   * sao lancadas como {@code JacksonException} (unchecked) e propagadas para que o {@code
   * DefaultErrorHandler} do container as encaminhe para a DLQ sem novas tentativas, ja que payload
   * invalido sempre sera invalido.
   *
   * @param payload conteudo JSON da mensagem recebida
   */
  @KafkaListener(topics = "${app.kafka.topico-pagamento-status-atualizado}", groupId = "assinatura")
  public void consumir(String payload) {
    PagamentoStatusAtualizado evento =
        objectMapper.readValue(payload, PagamentoStatusAtualizado.class);
    processarPagamento.executar(evento);
  }
}
