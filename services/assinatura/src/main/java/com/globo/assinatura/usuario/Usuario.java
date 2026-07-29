package com.globo.assinatura.usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Representa o usuario do dominio de assinatura.
 *
 * <p>Agregado de persistencia responsavel por armazenar a identidade de um usuario, composta pelo
 * identificador tecnico gerado pelo banco, pelo uuid publico atribuido em memoria e pelos dados de
 * contato basico.
 */
@Entity
@Table(name = "usuarios")
public class Usuario {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String uuid = UUID.randomUUID().toString();

  private String nome;

  private String email;

  /** Construtor sem argumentos exigido pelo provedor JPA; o uuid e atribuido pelo inicializador. */
  public Usuario() {}

  /**
   * Retorna o identificador tecnico gerado pelo banco de dados.
   *
   * @return identificador tecnico, ou {@code null} antes da persistencia
   */
  public Long getId() {
    return id;
  }

  /**
   * Atribui o identificador tecnico.
   *
   * @param id identificador tecnico
   */
  public void setId(Long id) {
    this.id = id;
  }

  /**
   * Retorna o uuid publico do usuario.
   *
   * @return uuid publico
   */
  public String getUuid() {
    return uuid;
  }

  /**
   * Atribui o uuid publico.
   *
   * @param uuid uuid publico
   */
  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  /**
   * Retorna o nome do usuario.
   *
   * @return nome do usuario
   */
  public String getNome() {
    return nome;
  }

  /**
   * Atribui o nome do usuario.
   *
   * @param nome nome do usuario
   */
  public void setNome(String nome) {
    this.nome = nome;
  }

  /**
   * Retorna o email do usuario.
   *
   * @return email do usuario
   */
  public String getEmail() {
    return email;
  }

  /**
   * Atribui o email do usuario.
   *
   * @param email email do usuario
   */
  public void setEmail(String email) {
    this.email = email;
  }
}
