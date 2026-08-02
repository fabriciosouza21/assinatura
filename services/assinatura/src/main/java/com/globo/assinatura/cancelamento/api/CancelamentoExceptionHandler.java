package com.globo.assinatura.cancelamento.api;

import com.globo.assinatura.assinatura.AssinaturaNaoEncontradaException;
import com.globo.assinatura.shared.seguranca.AcessoNegadoException;
import com.globo.assinatura.shared.web.Problem;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Trata as excecoes da capacidade de cancelamento expostas pela API HTTP.
 *
 * <p>Mapeia os erros de dominio do cancelamento (assinatura inexistente, acesso negado) e os erros
 * de formato do uuid da rota para os status HTTP correspondentes.
 */
@RestControllerAdvice(assignableTypes = CancelamentoController.class)
public class CancelamentoExceptionHandler {

  /**
   * Mapeia uma assinatura ausente para {@code 404 Not Found}.
   *
   * @param ex excecao de assinatura inexistente
   * @return resposta sem corpo com status {@code 404}
   */
  @ExceptionHandler(AssinaturaNaoEncontradaException.class)
  public ResponseEntity<Void> tratarAssinaturaNaoEncontrada(AssinaturaNaoEncontradaException ex) {
    return ResponseEntity.notFound().build();
  }

  /**
   * Mapeia acesso a assinatura de terceiro para {@code 403 Forbidden}.
   *
   * @param ex excecao de autorizacao
   * @return resposta sem corpo com status {@code 403}
   */
  @ExceptionHandler(AcessoNegadoException.class)
  public ResponseEntity<Void> tratarAcessoNegado(AcessoNegadoException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
  }

  /**
   * Mapeia uuid malformado para {@code 400 Bad Request}.
   *
   * @param ex excecao lancada quando o uuid nao pode ser convertido
   * @return problem com o erro de formato do uuid
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Problem> tratarUuidMalformado(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            new Problem(
                400,
                "Requisicao invalida",
                List.of(new Problem.FieldError("uuid", "deve ser um uuid valido"))));
  }
}
