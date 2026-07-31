package com.globo.assinatura.assinatura;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller de assinatura exposto em {@code /assinaturas}. */
@RestController
@RequestMapping("/assinaturas")
public class AssinaturaController {

  private static final Logger log = LoggerFactory.getLogger(AssinaturaController.class);

  private final SolicitarAssinatura solicitarAssinatura;
  private final ConsultarAssinatura consultarAssinatura;

  /**
   * Cria o controller com o command e a query de assinatura.
   *
   * @param solicitarAssinatura command de solicitacao de assinatura
   * @param consultarAssinatura query de consulta de assinatura
   */
  public AssinaturaController(
      SolicitarAssinatura solicitarAssinatura, ConsultarAssinatura consultarAssinatura) {
    this.solicitarAssinatura = solicitarAssinatura;
    this.consultarAssinatura = consultarAssinatura;
  }

  /**
   * Solicita uma assinatura.
   *
   * @param request dados da solicitacao
   * @return a assinatura criada, com status 202 Accepted
   */
  @PostMapping
  public ResponseEntity<AssinaturaCriadaResponse> solicitar(
      @Valid @RequestBody AssinaturaRequest request) {
    log.info(
        "Solicitacao de assinatura recebida para usuarioId={} e plano={}",
        request.usuarioId(),
        request.plano());
    Assinatura assinatura = solicitarAssinatura.executar(request.usuarioId(), request.plano());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new AssinaturaCriadaResponse(assinatura.getUuid(), assinatura.getStatus()));
  }

  /**
   * Consulta uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a representacao completa da assinatura, com status 200 OK
   */
  @GetMapping("/{uuid}")
  public AssinaturaResponse consultar(@PathVariable String uuid) {
    return consultarAssinatura.executar(uuid);
  }
}
