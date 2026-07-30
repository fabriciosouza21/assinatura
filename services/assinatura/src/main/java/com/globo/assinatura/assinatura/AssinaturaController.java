package com.globo.assinatura.assinatura;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller de assinatura exposto em {@code /assinaturas}. */
@RestController
@RequestMapping("/assinaturas")
public class AssinaturaController {

  private final AssinaturaService service;

  /**
   * Cria o controller com o servico de assinatura.
   *
   * @param service o servico de assinatura
   */
  public AssinaturaController(AssinaturaService service) {
    this.service = service;
  }

  /**
   * Solicita uma assinatura.
   *
   * @param request dados da solicitacao
   * @return a assinatura criada, com status 202 Accepted
   */
  @PostMapping
  public ResponseEntity<AssinaturaCriadaResponse> solicitar(
      @RequestBody AssinaturaRequest request) {
    Assinatura assinatura = service.solicitar(request.usuarioId(), request.plano());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new AssinaturaCriadaResponse(assinatura.getUuid(), assinatura.getStatus()));
  }
}
