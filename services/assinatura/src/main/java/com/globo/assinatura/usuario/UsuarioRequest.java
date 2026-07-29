package com.globo.assinatura.usuario;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Dados enviados no cadastro de um usuario novo.
 *
 * @param nome nome do usuario; nao pode ser vazio
 * @param email email do usuario; nao pode ser vazio e deve estar em formato valido
 */
public record UsuarioRequest(@NotBlank String nome, @NotBlank @Email String email) {}
