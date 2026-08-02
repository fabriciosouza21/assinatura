package com.globo.pagamento.adesao.evento;

import com.globo.pagamento.adesao.CriarCobrancaAdesao;
import com.globo.pagamento.shared.contrato.AssinaturaSolicitada;
import com.globo.pagamento.shared.kafka.EventoInvalidoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer do topico {@code assinatura-solicitada}.
 *
 * <p>Desserializa o JSON do evento, valida os campos obrigatorios e delega a criacao da cobranca ao
 * {@link CriarCobrancaAdesao}. Eventos invalidos disparam {@link EventoInvalidoException}, tratada
 * como nao retentavel pelo {@code DefaultErrorHandler} (vai direto para a DLQ).
 */
@Component
public class AssinaturaSolicitadaConsumer {

  private static final Logger log = LoggerFactory.getLogger(AssinaturaSolicitadaConsumer.class);

  private final ObjectMapper objectMapper;
  private final CriarCobrancaAdesao criarCobrancaService;

  /**
   * Cria o consumer.
   *
   * @param objectMapper mapper JSON para desserializar o evento
   * @param criarCobrancaService service de criacao de cobranca
   */
  public AssinaturaSolicitadaConsumer(
      ObjectMapper objectMapper, CriarCobrancaAdesao criarCobrancaService) {
    this.objectMapper = objectMapper;
    this.criarCobrancaService = criarCobrancaService;
  }

  /**
   * Consome uma mensagem do topico {@code assinatura-solicitada}.
   *
   * @param payload JSON do evento {@link AssinaturaSolicitada}
   */
  @KafkaListener(topics = "assinatura-solicitada", groupId = "pagamento")
  public void consumir(String payload) {
    AssinaturaSolicitada evento = desserializar(payload);
    validar(evento);
    log.atDebug()
        .addKeyValue("event", "assinatura_solicitada_recebida")
        .addKeyValue("assinaturaId", evento.assinaturaId())
        .log("Evento de assinatura solicitada recebido");
    criarCobrancaService.processar(evento);
  }

  private AssinaturaSolicitada desserializar(String payload) {
    try {
      return objectMapper.readValue(payload, AssinaturaSolicitada.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private void validar(AssinaturaSolicitada evento) {
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.usuarioId() == null) {
      throw new EventoInvalidoException("usuarioId ausente");
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
  }
}
