package com.globo.assinatura.adesao.evento;

import com.globo.assinatura.adesao.FalharPagamentoAdesao;
import com.globo.assinatura.shared.contrato.AssinaturaAdesaoEsgotada;
import com.globo.assinatura.shared.kafka.EventoInvalidoException;
import com.globo.assinatura.shared.kafka.MessagingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Kafka do topico de resultado da adesao.
 *
 * <p>Desserializa o payload JSON do evento {@link AssinaturaAdesaoEsgotada}, valida os campos
 * obrigatorios e delega o processamento de dominio ao command {@link FalharPagamentoAdesao}.
 * Eventos invalidos disparam {@link EventoInvalidoException}, tratada como nao retentavel pelo
 * {@code DefaultErrorHandler} configurado em {@link MessagingConfig} (vai direto para a DLQ).
 */
@Component
public class AdesaoResultadoConsumer {

  private static final Logger log = LoggerFactory.getLogger(AdesaoResultadoConsumer.class);

  private final FalharPagamentoAdesao falharPagamentoAdesao;
  private final ObjectMapper objectMapper;

  /**
   * Constroi o consumer com o command e o mapeador JSON injetados.
   *
   * @param falharPagamentoAdesao command de processamento do esgotamento de adesao
   * @param objectMapper mapeador JSON para desserializar o payload recebido
   */
  public AdesaoResultadoConsumer(
      FalharPagamentoAdesao falharPagamentoAdesao, ObjectMapper objectMapper) {
    this.falharPagamentoAdesao = falharPagamentoAdesao;
    this.objectMapper = objectMapper;
  }

  /**
   * Consome uma mensagem do topico de resultado da adesao.
   *
   * @param payload conteudo JSON da mensagem recebida
   */
  @KafkaListener(topics = "${app.kafka.topico-adesao-resultado}", groupId = "assinatura")
  public void consumir(String payload) {
    AssinaturaAdesaoEsgotada evento = desserializar(payload);
    validar(evento);
    falharPagamentoAdesao.executar(evento);
    log.atInfo()
        .addKeyValue("event", "adesao_esgotada_consumida")
        .addKeyValue("eventId", evento.eventId())
        .addKeyValue("assinaturaId", evento.assinaturaId())
        .log("Evento de adesao esgotada consumido");
  }

  private AssinaturaAdesaoEsgotada desserializar(String payload) {
    try {
      return objectMapper.readValue(payload, AssinaturaAdesaoEsgotada.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado");
    }
  }

  private void validar(AssinaturaAdesaoEsgotada evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
  }
}
