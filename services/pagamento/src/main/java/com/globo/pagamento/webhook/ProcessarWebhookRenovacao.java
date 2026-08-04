package com.globo.pagamento.webhook;

import com.globo.pagamento.renovacao.PagamentoRenovacao;
import com.globo.pagamento.renovacao.TentativaCobranca;
import com.globo.pagamento.renovacao.TentativaCobrancaRepository;
import com.globo.pagamento.shared.contrato.PagamentoRenovacaoAprovado;
import com.globo.pagamento.shared.contrato.RenovacaoTentativasEsgotadas;
import com.globo.pagamento.shared.contrato.StatusPagamento;
import com.globo.pagamento.shared.outbox.OutboxEvent;
import com.globo.pagamento.shared.outbox.OutboxRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Command que decide o resultado de uma cobranca de renovacao a partir do status oficial do
 * gateway.
 *
 * <p>Ancora a decisao na {@link TentativaCobranca} cobrada sob o {@code paymentId} notificado.
 * Aprovacao encerra o ciclo e grava {@link PagamentoRenovacaoAprovado} na outbox; recusa com
 * tentativas restantes agenda a proxima cobranca sem gravar nada; recusa na ultima tentativa esgota
 * o ciclo e grava {@link RenovacaoTentativasEsgotadas}. Um status ainda pendente no gateway nao
 * consome a tentativa.
 *
 * <p>A decisao e a gravacao na outbox acontecem na mesma transacao: se a transacao voltar, o evento
 * nao existe. O ack do Kafka sai do caminho do cliente e fica com o {@link
 * com.globo.pagamento.shared.outbox.OutboxPublisher}. Uma tentativa ja decidida e ignorada, o que
 * absorve o reprocesso de um webhook cuja decisao anterior ja tinha sido persistida.
 */
public class ProcessarWebhookRenovacao {

  private static final Logger log = LoggerFactory.getLogger(ProcessarWebhookRenovacao.class);

  private final TentativaCobrancaRepository tentativaRepository;
  private final ObjectMapper objectMapper;
  private final OutboxRepository outboxRepository;
  private final List<Integer> backoffDias;

  /**
   * Cria o command com suas dependencias.
   *
   * @param tentativaRepository repositorio das tentativas de cobranca
   * @param objectMapper mapeador JSON para serializar o evento gravado na outbox
   * @param outboxRepository repositorio da outbox
   * @param backoffDias uma entrada por <em>espera</em> entre tentativas: o valor de indice {@code
   *     numero - 1} e a espera, em dias, entre a recusa da tentativa {@code numero} e a tentativa
   *     seguinte. Como a ultima tentativa nao agenda nada, o ciclo tem uma tentativa a mais que o
   *     tamanho da lista: o default {@code 1,3} rende tres tentativas, em D+0, D+1 e D+3
   * @throws IllegalArgumentException se {@code backoffDias} for nulo, vazio ou contiver valor nulo
   *     ou negativo
   */
  public ProcessarWebhookRenovacao(
      TentativaCobrancaRepository tentativaRepository,
      ObjectMapper objectMapper,
      OutboxRepository outboxRepository,
      List<Integer> backoffDias) {
    if (backoffDias == null || backoffDias.isEmpty()) {
      throw new IllegalArgumentException("backoffDias deve ter ao menos uma entrada");
    }
    if (backoffDias.stream().anyMatch(dias -> dias == null || dias < 0)) {
      throw new IllegalArgumentException("backoffDias nao pode conter valor negativo");
    }
    this.tentativaRepository = tentativaRepository;
    this.objectMapper = objectMapper;
    this.outboxRepository = outboxRepository;
    this.backoffDias = List.copyOf(backoffDias);
  }

  /**
   * Decide o resultado da tentativa cobrada sob o {@code paymentId} notificado.
   *
   * <p>A tentativa e travada para escrita ate o fim da transacao: notificacoes concorrentes sobre a
   * mesma cobranca (reenvio do gateway, replay) ficam serializadas, e a segunda enxerga a tentativa
   * ja decidida e a ignora.
   *
   * @param pagamento pagamento da renovacao correlacionado ao {@code externalReference} do webhook
   * @param eventId identificador unico do evento (= {@code X-Mock-Event-Id})
   * @param paymentId identificador da cobranca no gateway
   * @param status status oficial do gateway ja normalizado
   * @throws DecisaoRenovacaoIndisponivelException se nenhuma tentativa tiver sido cobrada sob o
   *     {@code paymentId} notificado, ou se a tentativa pertencer a outra renovacao
   */
  @Transactional
  public void decidir(
      PagamentoRenovacao pagamento, UUID eventId, String paymentId, StatusPagamento status) {
    Optional<TentativaCobranca> encontrada =
        tentativaRepository.buscarPorPaymentIdParaAtualizacao(paymentId);
    if (encontrada.isEmpty()) {
      log.atWarn()
          .addKeyValue("event", "resultado_renovacao_sem_tentativa")
          .addKeyValue("renovacaoId", pagamento.getRenovacaoId())
          .addKeyValue("paymentId", paymentId)
          .addKeyValue("reasonCode", "tentativa_nao_encontrada")
          .log("Nenhuma tentativa cobrada sob o payment id notificado, aguardando reenvio");
      throw new DecisaoRenovacaoIndisponivelException();
    }
    TentativaCobranca tentativa = encontrada.get();
    if (!pagamento.getRenovacaoId().equals(tentativa.getRenovacaoId())) {
      log.atWarn()
          .addKeyValue("event", "resultado_renovacao_sem_tentativa")
          .addKeyValue("renovacaoId", pagamento.getRenovacaoId())
          .addKeyValue("paymentId", paymentId)
          .addKeyValue("reasonCode", "renovacao_divergente")
          .addKeyValue("tentativaRenovacaoId", tentativa.getRenovacaoId())
          .log("Tentativa do payment id notificado pertence a outra renovacao, aguardando reenvio");
      throw new DecisaoRenovacaoIndisponivelException();
    }
    if (!tentativa.estaPendente()) {
      log.atDebug()
          .addKeyValue("event", "resultado_renovacao_ja_decidido")
          .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
          .addKeyValue("paymentId", paymentId)
          .addKeyValue("numero", tentativa.getNumero())
          .addKeyValue("statusTentativa", tentativa.getStatus())
          .log("Tentativa ja decidida, notificacao ignorada");
      return;
    }
    switch (status) {
      case APPROVED -> aprovar(pagamento, tentativa, eventId, paymentId);
      case REJECTED -> recusar(pagamento, tentativa, eventId, paymentId);
      default ->
          log.atDebug()
              .addKeyValue("event", "resultado_renovacao_pendente")
              .addKeyValue("renovacaoId", tentativa.getRenovacaoId())
              .addKeyValue("paymentId", paymentId)
              .addKeyValue("numero", tentativa.getNumero())
              .log("Cobranca da renovacao segue pendente no gateway");
    }
  }

  private void aprovar(
      PagamentoRenovacao pagamento, TentativaCobranca tentativa, UUID eventId, String paymentId) {
    tentativa.aprovar();
    tentativaRepository.save(tentativa);
    gravarOutbox(
        eventId,
        pagamento,
        "PagamentoRenovacaoAprovado",
        "APROVADO",
        new PagamentoRenovacaoAprovado(
            eventId,
            Instant.now(),
            UUID.fromString(pagamento.getRenovacaoId()),
            UUID.fromString(pagamento.getAssinaturaId()),
            paymentId,
            pagamento.getCicloReferencia()));
    log.atInfo()
        .addKeyValue("event", "renovacao_aprovada")
        .addKeyValue("renovacaoId", pagamento.getRenovacaoId())
        .addKeyValue("assinaturaId", pagamento.getAssinaturaId())
        .addKeyValue("paymentId", paymentId)
        .addKeyValue("numero", tentativa.getNumero())
        .log("Renovacao aprovada pelo gateway");
  }

  private void recusar(
      PagamentoRenovacao pagamento, TentativaCobranca tentativa, UUID eventId, String paymentId) {
    if (tentativa.getNumero() > backoffDias.size()) {
      tentativa.esgotar();
      tentativaRepository.save(tentativa);
      gravarOutbox(
          eventId,
          pagamento,
          "RenovacaoTentativasEsgotadas",
          "ESGOTADO",
          new RenovacaoTentativasEsgotadas(
              eventId,
              Instant.now(),
              UUID.fromString(pagamento.getRenovacaoId()),
              UUID.fromString(pagamento.getAssinaturaId()),
              pagamento.getCicloReferencia()));
      log.atInfo()
          .addKeyValue("event", "renovacao_tentativas_esgotadas")
          .addKeyValue("renovacaoId", pagamento.getRenovacaoId())
          .addKeyValue("assinaturaId", pagamento.getAssinaturaId())
          .addKeyValue("paymentId", paymentId)
          .addKeyValue("numero", tentativa.getNumero())
          .log("Renovacao esgotou as tentativas de cobranca");
      return;
    }
    tentativa.recusar();
    tentativaRepository.save(tentativa);
    Instant proximaEm =
        Instant.now().plus(backoffDias.get(tentativa.getNumero() - 1), ChronoUnit.DAYS);
    TentativaCobranca proxima = pagamento.registrarTentativa(tentativa, proximaEm);
    tentativaRepository.save(proxima);
    log.atInfo()
        .addKeyValue("event", "renovacao_tentativa_recusada")
        .addKeyValue("renovacaoId", pagamento.getRenovacaoId())
        .addKeyValue("paymentId", paymentId)
        .addKeyValue("numero", tentativa.getNumero())
        .addKeyValue("proximoNumero", proxima.getNumero())
        .addKeyValue("proximaTentativaEm", proximaEm)
        .log("Cobranca da renovacao recusada, proxima tentativa agendada");
  }

  private void gravarOutbox(
      UUID eventId, PagamentoRenovacao pagamento, String eventType, String tipo, Object evento) {
    ObjectNode envelope = objectMapper.createObjectNode();
    ObjectNode corpo = objectMapper.valueToTree(evento);
    envelope.put("tipo", tipo);
    envelope.setAll(corpo);
    String payload = objectMapper.writeValueAsString(envelope);
    outboxRepository.save(
        OutboxEvent.criar(
            eventId,
            "Renovacao",
            UUID.fromString(pagamento.getAssinaturaId()),
            eventType,
            payload));
  }
}
