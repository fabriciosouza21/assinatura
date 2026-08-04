package com.globo.pagamento.webhook;

import com.globo.pagamento.gateway.StatusGateway;
import com.globo.pagamento.shared.contrato.StatusPagamento;
import org.springframework.stereotype.Component;

/**
 * Normaliza o status reportado pelo gateway para o dominio publicado no evento.
 *
 * <p>{@code APPROVED} e {@code PENDING} seguem com o mesmo nome; {@code REJECTED}, {@code
 * CANCELLED} e {@code EXPIRED} viram {@code REJECTED}.
 */
@Component
public class NormalizadorStatus {

  /**
   * Normaliza o status do gateway para o status publicado.
   *
   * @param status status oficial reportado pelo gateway
   * @return status normalizado em {@code APPROVED}, {@code REJECTED} ou {@code PENDING}
   */
  public StatusPagamento normalizar(StatusGateway status) {
    return switch (status) {
      case APPROVED, PENDING -> StatusPagamento.valueOf(status.name());
      case REJECTED, CANCELLED, EXPIRED -> StatusPagamento.REJECTED;
    };
  }
}
