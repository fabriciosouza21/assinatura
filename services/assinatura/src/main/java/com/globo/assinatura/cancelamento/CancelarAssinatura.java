package com.globo.assinatura.cancelamento;

import com.globo.assinatura.assinatura.AcessoNegadoException;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.EfeitoCancelamento;
import com.globo.assinatura.cancelamento.api.CancelamentoResponse;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.security.UsuarioAutenticado;
import com.globo.assinatura.shared.contrato.CancelamentoAgendado;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Command que solicita o cancelamento de uma assinatura pelo proprio dono. */
@Service
public class CancelarAssinatura {

  private static final String AGGREGATE_TYPE = "Assinatura";
  private static final String EVENT_TYPE_CANCELAMENTO_AGENDADO = "CancelamentoAgendado";

  private final AssinaturaRepository assinaturaRepository;
  private final UsuarioRepository usuarioRepository;
  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;

  /**
   * Constroi o command com os repositorios e o serializador JSON injetados.
   *
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON dos eventos de dominio
   */
  public CancelarAssinatura(
      AssinaturaRepository assinaturaRepository,
      UsuarioRepository usuarioRepository,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper) {
    this.assinaturaRepository = assinaturaRepository;
    this.usuarioRepository = usuarioRepository;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Solicita o cancelamento de uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @param principal identidade extraida do token JWT
   * @return estado da assinatura apos a solicitacao de cancelamento
   * @throws AssinaturaNaoEncontradaException se a assinatura ou o dono nao forem encontrados
   * @throws AcessoNegadoException se o principal nao for o dono da assinatura
   */
  @Transactional
  public CancelamentoResponse executar(String uuid, UsuarioAutenticado principal) {
    Assinatura assinatura =
        assinaturaRepository
            .buscarPorUuidParaAtualizacao(uuid)
            .orElseThrow(AssinaturaNaoEncontradaException::new);
    Usuario dono =
        usuarioRepository
            .findById(assinatura.getUsuarioId())
            .orElseThrow(AssinaturaNaoEncontradaException::new);
    if (!dono.getUuid().equals(principal.usuarioId())) {
      throw new AcessoNegadoException();
    }

    EfeitoCancelamento efeito = assinatura.solicitarCancelamento();
    if (efeito == EfeitoCancelamento.AGENDADO) {
      gravarEventoCancelamentoAgendado(assinatura);
    }

    return new CancelamentoResponse(
        assinatura.getUuid(),
        assinatura.getStatus(),
        assinatura.isRenovacaoAutomatica(),
        assinatura.getFimCiclo());
  }

  private void gravarEventoCancelamentoAgendado(Assinatura assinatura) {
    CancelamentoAgendado evento =
        new CancelamentoAgendado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            assinatura.getStatus(),
            assinatura.getFimCiclo());
    outboxRepository.save(
        OutboxEvent.criar(
            evento.eventId(),
            AGGREGATE_TYPE,
            evento.assinaturaId(),
            EVENT_TYPE_CANCELAMENTO_AGENDADO,
            serializar(evento)));
  }

  private String serializar(CancelamentoAgendado evento) {
    try {
      return jsonMapper.writeValueAsString(evento);
    } catch (JacksonException e) {
      throw new IllegalStateException("Falha ao serializar evento CancelamentoAgendado", e);
    }
  }
}
