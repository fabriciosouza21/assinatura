package com.globo.pagamento.cancelamento.evento;

import com.globo.pagamento.cancelamento.CancelarTentativasPendentes;
import com.globo.pagamento.cancelamento.idempotencia.CancelamentoEventoProcessadoRepository;
import com.globo.pagamento.shared.contrato.AssinaturaCancelada;
import com.globo.pagamento.shared.contrato.CancelamentoAgendado;
import com.globo.pagamento.shared.kafka.EventoInvalidoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer dos eventos {@link CancelamentoAgendado} e {@link AssinaturaCancelada}.
 *
 * <p>O cancelamento agendado e apenas validado: a cobranca em curso do ciclo segue ate a decisao do
 * gateway, e o encerramento no fim do ciclo e efetivado pelo scheduler de vencimentos do
 * Assinatura. O cancelamento efetivado reserva o identificador do evento para processar cada
 * cancelamento uma unica vez e cancela as tentativas pendentes da assinatura.
 */
@Component
public class CancelamentoConsumer {

  private static final Logger log = LoggerFactory.getLogger(CancelamentoConsumer.class);

  private final ObjectMapper objectMapper;
  private final CancelarTentativasPendentes cancelarTentativasPendentes;
  private final CancelamentoEventoProcessadoRepository eventoProcessadoRepository;

  /**
   * Cria o consumer com o desserializador, o command e o repositorio de idempotencia.
   *
   * @param objectMapper mapper JSON para desserializar o evento
   * @param cancelarTentativasPendentes command que cancela as tentativas pendentes
   * @param eventoProcessadoRepository repositorio que reserva os eventos processados
   */
  public CancelamentoConsumer(
      ObjectMapper objectMapper,
      CancelarTentativasPendentes cancelarTentativasPendentes,
      CancelamentoEventoProcessadoRepository eventoProcessadoRepository) {
    this.objectMapper = objectMapper;
    this.cancelarTentativasPendentes = cancelarTentativasPendentes;
    this.eventoProcessadoRepository = eventoProcessadoRepository;
  }

  /**
   * Consome um evento do topico {@code cancelamento-agendado}.
   *
   * <p>O cancelamento agendado nao altera o pagamento: a cobranca do ciclo em curso segue ate a
   * decisao do gateway, e o encerramento da assinatura no fim do ciclo e efetivado pelo scheduler
   * de vencimentos do Assinatura, que publica {@code AssinaturaCancelada}.
   *
   * @param payload JSON do evento {@link CancelamentoAgendado}
   */
  @KafkaListener(topics = "cancelamento-agendado", groupId = "pagamento")
  public void consumirCancelamentoAgendado(String payload) {
    CancelamentoAgendado evento = desserializar(payload);
    validar(evento);
    log.atDebug()
        .addKeyValue("event", "cancelamento_agendado_recebido")
        .addKeyValue("assinaturaId", evento.assinaturaId())
        .log("Cancelamento agendado recebido sem acao no pagamento");
  }

  /**
   * Consome um evento do topico {@code assinatura-cancelada}.
   *
   * <p>Reserva o identificador do evento para processar cada cancelamento uma unica vez e, quando e
   * uma ocorrencia nova, cancela as tentativas pendentes da assinatura na mesma transacao.
   *
   * @param payload JSON do evento {@link AssinaturaCancelada}
   */
  @KafkaListener(topics = "assinatura-cancelada", groupId = "pagamento")
  @Transactional
  public void consumirAssinaturaCancelada(String payload) {
    AssinaturaCancelada evento = desserializarAssinaturaCancelada(payload);
    validar(evento);
    if (eventoProcessadoRepository.registrarSeNovo(evento.eventId(), evento.assinaturaId()) == 1) {
      cancelarTentativasPendentes.executar(evento.assinaturaId().toString());
      log.atInfo()
          .addKeyValue("event", "tentativas_canceladas")
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Tentativas pendentes canceladas");
    } else {
      log.atDebug()
          .addKeyValue("event", "cancelamento_evento_deduplicado")
          .addKeyValue("assinaturaId", evento.assinaturaId())
          .log("Cancelamento ja processado, evento ignorado");
    }
  }

  private CancelamentoAgendado desserializar(String payload) {
    try {
      return objectMapper.readValue(payload, CancelamentoAgendado.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private AssinaturaCancelada desserializarAssinaturaCancelada(String payload) {
    try {
      return objectMapper.readValue(payload, AssinaturaCancelada.class);
    } catch (JacksonException e) {
      throw new EventoInvalidoException("evento mal formado: " + e.getMessage());
    }
  }

  private void validar(CancelamentoAgendado evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.ocorridoEm() == null) {
      throw new EventoInvalidoException("ocorridoEm ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.status() == null) {
      throw new EventoInvalidoException("status ausente");
    }
  }

  private void validar(AssinaturaCancelada evento) {
    if (evento.eventId() == null) {
      throw new EventoInvalidoException("eventId ausente");
    }
    if (evento.ocorridoEm() == null) {
      throw new EventoInvalidoException("ocorridoEm ausente");
    }
    if (evento.assinaturaId() == null) {
      throw new EventoInvalidoException("assinaturaId ausente");
    }
    if (evento.status() == null) {
      throw new EventoInvalidoException("status ausente");
    }
  }
}
