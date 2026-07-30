package com.globo.assinatura.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador global de erros de requisicao, no formato RFC 7807/9457.
 *
 * <p>Converte falhas de validacao de campos em uma resposta {@code 400 Bad Request} com corpo
 * {@link Problem}, listando cada campo invalido para o cliente corrigir a requisicao. Um valor
 * invalido em um campo enum tambem e tratado aqui: o enum desserializa para {@code null} e a
 * validacao de obrigatoriedade o flagga.
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

  /**
   * Mapeia {@link MethodArgumentNotValidException} para {@code 400} com {@link Problem}.
   *
   * @param ex excecao lancada quando a validacao de um campo falha
   * @return resposta com status {@code 400} e o corpo detalhando os campos invalidos
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Problem> tratarValidacao(MethodArgumentNotValidException ex) {
    List<Problem.FieldError> erros =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new Problem.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new Problem(400, "Requisicao invalida", erros));
  }
}
