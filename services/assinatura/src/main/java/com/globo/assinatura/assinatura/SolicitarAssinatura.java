package com.globo.assinatura.assinatura;

import com.globo.assinatura.messaging.event.AssinaturaSolicitada;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Command de solicitacao de assinatura.
 *
 * <p>Cria uma assinatura para o usuario e o plano informados, aplicando a regra "um usuario, uma
 * assinatura aberta", e grava o evento {@link AssinaturaSolicitada} na outbox na mesma transacao,
 * tornando o insert da assinatura e o registro do evento atomicos. Responsavel apenas por escrita.
 */
@Service
public class SolicitarAssinatura {

  private static final String AGGREGATE_TYPE = "Assinatura";
  private static final String EVENT_TYPE = "AssinaturaSolicitada";
  private static final List<StatusAssinatura> STATUS_ABERTOS =
      List.of(StatusAssinatura.AGUARDANDO_PAGAMENTO, StatusAssinatura.ATIVA);

  private final UsuarioRepository usuarioRepository;
  private final AssinaturaRepository assinaturaRepository;
  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;

  /**
   * Constroi o command com os repositorios e o serializador JSON injetados.
   *
   * @param usuarioRepository repositorio de persistencia de usuarios
   * @param assinaturaRepository repositorio de persistencia de assinaturas
   * @param outboxRepository repositorio de persistencia da outbox
   * @param jsonMapper serializador JSON do evento de dominio
   */
  public SolicitarAssinatura(
      UsuarioRepository usuarioRepository,
      AssinaturaRepository assinaturaRepository,
      OutboxRepository outboxRepository,
      JsonMapper jsonMapper) {
    this.usuarioRepository = usuarioRepository;
    this.assinaturaRepository = assinaturaRepository;
    this.outboxRepository = outboxRepository;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Solicita uma assinatura para o usuario e o plano informados.
   *
   * @param usuarioUuid uuid publico do usuario que solicita a assinatura
   * @param plano plano contratado
   * @return a assinatura criada
   * @throws UsuarioNaoEncontradoException se o uuid nao corresponder a um usuario cadastrado
   * @throws AssinaturaAbertaException se o usuario ja possuir assinatura aberta
   */
  @Transactional
  public Assinatura executar(String usuarioUuid, Plano plano) {
    Usuario usuario =
        usuarioRepository.findByUuid(usuarioUuid).orElseThrow(UsuarioNaoEncontradoException::new);
    if (assinaturaRepository.existsByUsuarioIdAndStatusIn(usuario.getId(), STATUS_ABERTOS)) {
      throw new AssinaturaAbertaException();
    }
    Assinatura assinatura = new Assinatura(usuario.getId(), plano);
    Assinatura persistida;
    try {
      persistida = assinaturaRepository.save(assinatura);
    } catch (DataIntegrityViolationException e) {
      throw new AssinaturaAbertaException();
    }
    gravarEvento(persistida, usuario, plano);
    return persistida;
  }

  private void gravarEvento(Assinatura assinatura, Usuario usuario, Plano plano) {
    AssinaturaSolicitada evento =
        new AssinaturaSolicitada(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            UUID.fromString(usuario.getUuid()),
            plano);
    outboxRepository.save(
        OutboxEvent.criar(
            evento.eventId(),
            AGGREGATE_TYPE,
            evento.assinaturaId(),
            EVENT_TYPE,
            serializar(evento)));
  }

  private String serializar(AssinaturaSolicitada evento) {
    try {
      return jsonMapper.writeValueAsString(evento);
    } catch (JacksonException e) {
      throw new IllegalStateException("Falha ao serializar evento AssinaturaSolicitada", e);
    }
  }
}
