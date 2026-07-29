package com.globo.assinatura.usuario;

/** Dados enviados no cadastro de um usuario novo. */
public record UsuarioRequest(String nome, String email) {}
