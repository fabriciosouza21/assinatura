package com.globo.pagamento.cancelamento.evento;

import com.globo.pagamento.cancelamento.CancelarTentativasPendentes;
import com.globo.pagamento.cancelamento.idempotencia.CancelamentoEventoProcessadoRepository;
import com.globo.pagamento.messaging.EventoInvalidoException;
import com.globo.pagamento.shared.contrato.AssinaturaCancelada;
import com.globo.pagamento.shared.contrato.CancelamentoAgendado;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer dos eventos {@link CancelamentoAgendado} e {@link AssinaturaCancelada}.
 *
 * <p>Reserva o identificador do evento para processar cada cancelamento uma unica vez e cancela as
 * tentativas pendentes da assinatura.
 */
@Component
public class CancelamentoConsumer {

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
   * @param payload JSON do evento {@link CancelamentoAgendado}
   */
  @KafkaListener(topics = "cancelamento-agendado", groupId = "pagamento")
  @Transactional
  public void consumirCancelamentoAgendado(String payload) {
    CancelamentoAgendado evento = desserializar(payload);
    validar(evento);
    processar(evento.eventId(), evento.assinaturaId());
  }

  /**
   * Consome um evento do topico {@code assinatura-cancelada}.
   *
   * @param payload JSON do evento {@link AssinaturaCancelada}
   */
  @KafkaListener(topics = "assinatura-cancelada", groupId = "pagamento")
  @Transactional
  public void consumirAssinaturaCancelada(String payload) {
    AssinaturaCancelada evento = desserializarAssinaturaCancelada(payload);
    validar(evento);
    processar(evento.eventId(), evento.assinaturaId());
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

  private void processar(UUID eventId, UUID assinaturaId) {
    if (eventoProcessadoRepository.registrarSeNovo(eventId, assinaturaId) == 1) {
      cancelarTentativasPendentes.executar(assinaturaId.toString());
    }
  }
}
