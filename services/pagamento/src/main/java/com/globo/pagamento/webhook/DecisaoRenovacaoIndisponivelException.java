package com.globo.pagamento.webhook;

/**
 * Lancada quando o webhook nao encontra a tentativa cobrada sob o {@code paymentId} notificado,
 * sinalizando ao gateway que deve reenviar a notificacao.
 *
 * <p>O caso acontece quando a notificacao chega antes de o scheduler commitar o {@code paymentId}
 * na tentativa. Absorver a notificacao seria perder a decisao para sempre: o {@code eventId}
 * entraria na deduplicacao, o reenvio do gateway seria descartado e a tentativa ficaria {@code
 * PENDENTE} com {@code paymentId} preenchido, fora do alcance do scheduler. Por isso o webhook
 * falha e deixa o gateway reenviar.
 */
public class DecisaoRenovacaoIndisponivelException extends RuntimeException {

  /** Construtor padrao. */
  public DecisaoRenovacaoIndisponivelException() {
    super("decisao_renovacao_indisponivel");
  }
}
