package com.globo.pagamento;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada do Pagamento Service.
 *
 * <p>Orquestra pagamentos junto ao gateway externo, recebe webhooks de confirmação e publica o
 * status final no Kafka para o Assinatura Service consumir.
 */
@SpringBootApplication
@EnableScheduling
public class PagamentoApplication {

  /**
   * Inicia a aplicação.
   *
   * @param args argumentos de linha de comando repassados ao Spring
   */
  public static void main(String[] args) {
    SpringApplication.run(PagamentoApplication.class, args);
  }
}
