package com.globo.assinatura.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * validacao de obrigatoriedade o flagga. Excecoes nao tratadas que escapam dos handlers especificos
 * sao registradas em nivel ERROR e devolvidas como {@code 500}.
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

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

  /**
   * Trata excecoes nao tratadas que escapam dos handlers especificos, registrando-as em nivel ERROR
   * com o stack completo antes de devolver {@code 500}.
   *
   * @param ex excecao nao tratada
   * @return resposta com status {@code 500} e o corpo padronizado
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> handleNaoTratada(Exception ex) {
    log.error("Excecao nao tratada na requisicao", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new Problem(500, "Erro interno", List.of()));
  }
}
