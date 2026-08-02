package com.globo.assinatura.cancelamento.api;

import com.globo.assinatura.web.Problem;
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
 * <p>Os erros de dominio (assinatura inexistente, acesso negado) sao mapeados pelo {@code
 * AssinaturaExceptionHandler} global; aqui ficam apenas os erros de formato do uuid.
 */
@RestControllerAdvice(assignableTypes = CancelamentoController.class)
public class CancelamentoExceptionHandler {

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
