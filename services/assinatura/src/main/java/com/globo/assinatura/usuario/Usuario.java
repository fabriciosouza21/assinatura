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

  private String uuid;

  private String nome;

  private String email;

  /**
   * Construtor sem argumentos exigido pelo provedor JPA.
   *
   * <p>Acesso protegido para desencorajar uso fora do provedor de persistencia. O uuid permanece
   * {@code null} ate ser atribuido pela coluna carregada do banco, evitando gerar um UUID
   * descartavel em cada hidratacao.
   */
  protected Usuario() {}

  /**
   * Cria um usuário com o nome e o e-mail informados.
   *
   * <p>O UUID público é gerado pela própria entidade durante a construção.
   *
   * @param nome nome do usuário; não pode ser nulo nem vazio
   * @param email e-mail do usuário; não pode ser nulo nem vazio
   * @throws IllegalArgumentException se o nome ou o e-mail for nulo ou vazio
   */
  public Usuario(String nome, String email) {
    if (nome == null || nome.isBlank()) {
      throw new IllegalArgumentException("nome nao pode ser vazio");
    }
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email nao pode ser vazio");
    }
    this.uuid = UUID.randomUUID().toString();
    this.nome = nome;
    this.email = email;
  }

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
