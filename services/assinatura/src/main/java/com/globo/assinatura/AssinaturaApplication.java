package com.globo.assinatura;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Ponto de entrada do servico de assinatura (Spring Boot). */
@SpringBootApplication
public class AssinaturaApplication {

  /**
   * Inicia a aplicacao.
   *
   * @param args argumentos de linha de comando repassados ao Spring
   */
  public static void main(String[] args) {
    SpringApplication.run(AssinaturaApplication.class, args);
  }
}
