package com.globo.pagamento.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Valida a assinatura HMAC do webhook recebido do gateway.
 *
 * <p>O header {@code X-Mock-Signature} e {@code sha256=} + HMAC-SHA256 do corpo bruto em
 * hexadecimal, usando a chave {@code app.gateway.webhook-secret}. A comparacao usa tempo constante
 * para evitar vazamento por timing.
 */
public class HmacSignatureValidator {

  private static final String PREFIXO = "sha256=";

  private final byte[] secret;

  /**
   * Cria o validador com a chave compartilhada com o gateway.
   *
   * @param secret chave HMAC configurada em {@code app.gateway.webhook-secret}
   */
  public HmacSignatureValidator(String secret) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Verifica se a assinatura informada bate com o HMAC do corpo.
   *
   * @param corpo corpo bruto da requisicao recebida do gateway
   * @param headerAssinatura valor do header {@code X-Mock-Signature}
   * @return {@code true} se a assinatura for valida; {@code false} caso ausente ou divergente
   */
  public boolean valido(byte[] corpo, String headerAssinatura) {
    if (headerAssinatura == null || !headerAssinatura.startsWith(PREFIXO)) {
      return false;
    }
    String esperada = PREFIXO + HexFormat.of().formatHex(hmac(corpo));
    byte[] esperadaBytes = esperada.getBytes(StandardCharsets.UTF_8);
    byte[] recebidaBytes = headerAssinatura.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(esperadaBytes, recebidaBytes);
  }

  private byte[] hmac(byte[] corpo) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(corpo);
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao calcular HMAC do webhook", e);
    }
  }
}
