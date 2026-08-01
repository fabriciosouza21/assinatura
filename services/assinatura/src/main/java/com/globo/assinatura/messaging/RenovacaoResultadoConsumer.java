package com.globo.assinatura.messaging;

import com.globo.assinatura.assinatura.ProcessarRenovacaoResultado;
import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import com.globo.assinatura.messaging.event.RenovacaoTentativasEsgotadas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer Kafka do topico de resultado de renovacao.
 *
 * <p>Desserializa o payload JSON discriminando pelo campo {@code tipo} ({@code APROVADO} ou {@code
 * ESGOTADO}), valida os campos obrigatorios e delega o processamento de dominio ao command {@link
 * ProcessarRenovacaoResultado}. Eventos invalidos disparam {@link EventoInvalidoException}, tratada
 * como nao retentavel pelo {@code DefaultErrorHandler} configurado em {@link MessagingConfig} (vai
 * direto para a DLQ).
 */
@Component
public class RenovacaoResultadoConsumer {

  private static final Logger log = LoggerFactory.getLogger(RenovacaoResultadoConsumer.class);

  private final ProcessarRenovacaoResultado processarRenovacaoResultado;
  private final ObjectMapper objectMapper;

  /**
   * Constroi o consumer com o command e o mapeador JSON injetados.
   *
   * @param processarRenovacaoResultado command de processamento do resultado de renovacao
   * @param objectMapper mapeador JSON para desserializar o payload recebido
   */
  public RenovacaoResultadoConsumer(
      ProcessarRenovacaoResultado processarRenovacaoResultado, ObjectMapper objectMapper) {
    this.processarRenovacaoResultado = processarRenovacaoResultado;
    this.objectMapper = objectMapper;
  }

  /**
   * Consome uma mensagem do topico de resultado de renovacao.
   *
   * @param payload conteudo JSON da mensagem recebida
   */
  @KafkaListener(topics = "${app.kafka.topico-renovacao-resultado}", groupId = "assinatura")
  public void consumir(String payload) {
    JsonNode arvore = desserializarArvore(payload);
    String tipo = extrairTipo(arvore);
    if ("APROVADO".equals(tipo)) {
      PagamentoRenovacaoAprovado evento = desserializar(arvore, PagamentoRenovacaoAprovado.class);
      validar(evento);
      processarRenovacaoResultado.executar(evento);
      log.atInfo()
          .addKeyValue("event", "renovacao_aprovada_processada")
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Renovacao aprovada processada");
    } else if ("ESGOTADO".equals(tipo)) {
      RenovacaoTentativasEsgotadas evento =
          desserializar(arvore, RenovacaoTentativasEsgotadas.class);
      validar(evento);
      processarRenovacaoResultado.executar(evento);
      log.atInfo()
          .addKeyValue("event", "renovacao_esgotada_processada")
          .addKeyValue("renovacaoId", evento.renovacaoId())
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Renovacao esgotada processada");
    } else {
      throw new EventoInvalidoException("tipo desconhecido ou ausente: " + tipo);
    }
  }

  private JsonNode desserializarArvore(String payload) {
    try {
      return objectMapper.readTree(payload);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private String extrairTipo(JsonNode arvore) {
    JsonNode noTipo = arvore.get("tipo");
    return noTipo == null ? null : noTipo.asText();
  }

  private <T> T desserializar(JsonNode arvore, Class<T> tipo) {
    try {
      return objectMapper.treeToValue(arvore, tipo);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private void validar(PagamentoRenovacaoAprovado evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.renovacaoId() == null) {
      throw new EventoInvalidoException("renovacaoId ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.paymentId() == null) {
      throw new EventoInvalidoException("paymentId ausente");
    }
  }

  private void validar(RenovacaoTentativasEsgotadas evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.renovacaoId() == null) {
      throw new EventoInvalidoException("renovacaoId ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
  }
}
