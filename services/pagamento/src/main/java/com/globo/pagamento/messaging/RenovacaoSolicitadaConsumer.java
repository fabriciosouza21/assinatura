package com.globo.pagamento.messaging;

import com.globo.pagamento.messaging.event.RenovacaoSolicitada;
import com.globo.pagamento.renovacao.CriarPagamentoRenovacaoService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer do topico {@code renovacao-solicitada}.
 *
 * <p>Desserializa o JSON do evento, valida os campos obrigatorios e delega a criacao do pagamento
 * de renovacao ao {@link CriarPagamentoRenovacaoService}. Eventos invalidos disparam {@link
 * EventoInvalidoException}, tratada como nao retentavel pelo {@code DefaultErrorHandler} (vai
 * direto para a DLQ).
 */
@Component
public class RenovacaoSolicitadaConsumer {

  private final ObjectMapper objectMapper;
  private final CriarPagamentoRenovacaoService criarPagamentoRenovacaoService;

  /**
   * Cria o consumer.
   *
   * @param objectMapper mapper JSON para desserializar o evento
   * @param criarPagamentoRenovacaoService service de criacao do pagamento de renovacao
   */
  public RenovacaoSolicitadaConsumer(
      ObjectMapper objectMapper, CriarPagamentoRenovacaoService criarPagamentoRenovacaoService) {
    this.objectMapper = objectMapper;
    this.criarPagamentoRenovacaoService = criarPagamentoRenovacaoService;
  }

  /**
   * Consome uma mensagem do topico {@code renovacao-solicitada}.
   *
   * @param payload JSON do evento {@link RenovacaoSolicitada}
   */
  @KafkaListener(topics = "renovacao-solicitada", groupId = "pagamento")
  public void consumir(String payload) {
    RenovacaoSolicitada evento = desserializar(payload);
    validar(evento);
    criarPagamentoRenovacaoService.processar(evento);
  }

  private RenovacaoSolicitada desserializar(String payload) {
    try {
      return objectMapper.readValue(payload, RenovacaoSolicitada.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private void validar(RenovacaoSolicitada evento) {
    if (evento.renovacaoId() == null) {
      throw new EventoInvalidoException("renovacaoId ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.plano() == null) {
      throw new EventoInvalidoException("plano ausente");
    }
    if (evento.valor() == null) {
      throw new EventoInvalidoException("valor ausente");
    }
    if (evento.valor().signum() <= 0) {
      throw new EventoInvalidoException("valor deve ser positivo");
    }
    if (evento.cicloReferencia() <= 0) {
      throw new EventoInvalidoException("cicloReferencia deve ser positivo");
    }
  }
}
