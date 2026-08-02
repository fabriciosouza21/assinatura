package com.globo.assinatura.cadastro.api;

import com.globo.assinatura.cadastro.EmailJaCadastradoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tratador de excecoes do dominio de usuario exposto aos endpoints REST.
 *
 * <p>Converte {@link EmailJaCadastradoException}, lancada pelo servico quando o email informado ja
 * esta em uso, em uma resposta HTTP {@code 409 Conflict}. O corpo da resposta e mantido vazio para
 * evitar vazar o email conflitante, que e dado pessoal sob a LGPD.
 */
@RestControllerAdvice(assignableTypes = UsuarioController.class)
public class EmailJaCadastradoExceptionHandler {

  /**
   * Mapeia {@link EmailJaCadastradoException} para {@code 409 Conflict}.
   *
   * @param ex excecao lancada ao tentar cadastrar um email ja existente
   * @return resposta com status {@code 409} e corpo vazio
   */
  @ExceptionHandler(EmailJaCadastradoException.class)
  public ResponseEntity<Void> tratarEmailJaCadastrado(EmailJaCadastradoException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).build();
  }
}
