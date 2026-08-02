package com.globo.assinatura.user;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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

  /** Construtor sem argumentos exigido pelo provedor JPA. */
  protected User() {}

  /**
   * Cria um usuário autenticável com as credenciais informadas.
   *
   * @param username nome de login do usuário; não pode ser nulo nem vazio
   * @param password senha já hasheada, pronta para persistir; não pode ser nula nem vazia
   * @param role papel do usuário; não pode ser nulo nem vazio
   * @param usuarioId id do {@code Usuario} de domínio ligado, ou {@code null} para o admin
   * @throws IllegalArgumentException se username, password ou role forem nulos ou vazios
   */
  public User(String username, String password, String role, Long usuarioId) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username nao pode ser vazio");
    }
    if (password == null || password.isBlank()) {
      throw new IllegalArgumentException("password nao pode ser vazio");
    }
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role nao pode ser vazio");
    }
    this.username = username;
    this.password = password;
    this.role = role;
    this.usuarioId = usuarioId;
  }

  /**
   * Retorna o identificador do usuário.
   *
   * @return identificador técnico do usuário
   */
  public Long getId() {
    return id;
  }

  /**
   * Retorna o nome de login do usuário.
   *
   * @return nome de login
   */
  public String getUsername() {
    return username;
  }

  /**
   * Retorna a senha hasheada do usuário.
   *
   * @return senha hasheada
   */
  public String getPassword() {
    return password;
  }

  /**
   * Retorna o papel do usuário.
   *
   * @return papel do usuário, como {@code "ROLE_USER"}
   */
  public String getRole() {
    return role;
  }

  /**
   * Retorna o id do {@code Usuario} de domínio ligado.
   *
   * @return id do usuário de domínio, ou {@code null} para o admin
   */
  public Long getUsuarioId() {
    return usuarioId;
  }

  /**
   * Retorna o instante de criação do registro.
   *
   * @return instante de criação
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * Retorna o instante da última atualização do registro.
   *
   * @return instante da última atualização
   */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Preenche os instantes de criação e atualização antes do primeiro persist. */
  @PrePersist
  void aoPersistir() {
    Instant agora = Instant.now();
    this.createdAt = agora;
    this.updatedAt = agora;
  }
}
