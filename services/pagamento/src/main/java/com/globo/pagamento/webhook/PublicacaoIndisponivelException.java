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

  /**
   * Constroi a excecao preservando a causa raiz da falha de publicacao ou consulta.
   *
   * @param cause causa raiz da falha tecnica
   */
  public PublicacaoIndisponivelException(Throwable cause) {
    super("publicacao_indisponivel", cause);
  }
}
