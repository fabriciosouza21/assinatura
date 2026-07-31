package com.globo.pagamento.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teste unitario do {@link HmacSignatureValidator}.
 *
 * <p>Verifica o calculo do {@code X-Mock-Signature} ({@code sha256=} + HMAC-SHA256 do corpo bruto
 * em hex) contra a chave configurada, reproduzindo o mesmo algoritmo do mock.
 */
class HmacSignatureValidatorTest {

  private final HmacSignatureValidator validator =
      new HmacSignatureValidator("mock-webhook-secret");

  @Test
  @DisplayName("Deve aceitar assinatura que bate com o corpo")
  void deveAceitarAssinaturaQueBateComCorpo() throws Exception {
    byte[] corpo = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);

    boolean valido = validator.valido(corpo, assinaturaEsperada(corpo, "mock-webhook-secret"));

    assertThat(valido).as("Assinatura valida aceita").isTrue();
  }

  @Test
  @DisplayName("Deve rejeitar assinatura divergente do corpo")
  void deveRejeitarAssinaturaDivergente() {
    byte[] corpo = "{\"id\":\"evt-1\"}".getBytes(StandardCharsets.UTF_8);

    boolean valido = validator.valido(corpo, "sha256=00000000000000000000000000000000");

    assertThat(valido).as("Assinatura divergente rejeitada").isFalse();
  }

  private static String assinaturaEsperada(byte[] corpo, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] hash = mac.doFinal(corpo);
    StringBuilder hex = new StringBuilder("sha256=");
    for (byte b : hash) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }
}
