package com.globo.assinatura.cadastro.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dados enviados no cadastro de um usuario novo.
 *
 * @param nome nome do usuario; nao pode ser vazio
 * @param email email do usuario; nao pode ser vazio e deve estar em formato valido
 * @param senha senha do usuario; nao pode ser vazia e deve ter no minimo 8 caracteres
 */
public record UsuarioRequest(
    @NotBlank String nome, @NotBlank @Email String email, @NotBlank @Size(min = 8) String senha) {}
