package com.globo.assinatura.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Usuário autenticável do serviço de assinatura. */
@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String username;

  private String password;

  private String role;

  private Long usuarioId;

  private Instant createdAt;

  private Instant updatedAt;

  /** Retorna o identificador do usuário. */
  public Long getId() {
    return id;
  }

  /** Retorna o nome de login do usuário. */
  public String getUsername() {
    return username;
  }

  /** Retorna a senha hasheada do usuário. */
  public String getPassword() {
    return password;
  }

  /** Retorna o papel do usuário, como {@code "ROLE_USER"}. */
  public String getRole() {
    return role;
  }

  /** Retorna o id do {@code Usuario} de domínio ligado, ou {@code null} para o admin. */
  public Long getUsuarioId() {
    return usuarioId;
  }

  /** Retorna o instante de criação do registro. */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Retorna o instante da última atualização do registro. */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Define o identificador do usuário. */
  public void setId(Long id) {
    this.id = id;
  }

  /** Define o nome de login do usuário. */
  public void setUsername(String username) {
    this.username = username;
  }

  /** Define a senha do usuário, em texto plano, a ser persistida. */
  public void setPassword(String password) {
    this.password = password;
  }

  /** Define o papel do usuário. */
  public void setRole(String role) {
    this.role = role;
  }

  /** Define o id do {@code Usuario} de domínio ligado. */
  public void setUsuarioId(Long usuarioId) {
    this.usuarioId = usuarioId;
  }

  /** Define o instante de criação do registro. */
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /** Define o instante da última atualização do registro. */
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
