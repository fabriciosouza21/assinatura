package com.globo.pagamento.gateway;

/**
 * Lancada quando uma chamada ao gateway de pagamento falha por motivo tecnico (indisponibilidade,
 * timeout, erro 5xx), sinalizando que a operacao deve ser tentada novamente no proximo ciclo, com a
 * mesma chave de idempotencia. Erros de programacao (mapeamento, dados inconsistentes) nao sao
 * capturados e propagam crus, distinguindo falha tecnica de bug.
 */
public class CobrancaGatewayIndisponivelException extends RuntimeException {

  /** Construtor padrao. */
  public CobrancaGatewayIndisponivelException() {
    super("cobranca_gateway_indisponivel");
  }
}
