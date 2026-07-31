package com.globo.pagamento.webhook;

/**
 * Lancada quando a publicacao do evento no Kafka falha ou quando a consulta de status ao gateway
 * falha, sinalizando ao gateway que deve reenviar a notificacao.
 */
public class PublicacaoIndisponivelException extends RuntimeException {

  /** Construtor padrao. */
  public PublicacaoIndisponivelException() {
    super("publicacao_indisponivel");
  }
}
