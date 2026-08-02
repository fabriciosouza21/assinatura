package com.globo.assinatura.cadastro.api;

import com.globo.assinatura.cadastro.CadastrarUsuario;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller de usuario exposto em {@code /usuarios}. */
@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

  private final CadastrarUsuario usuarioService;

  /**
   * Cria o controller com o servico de usuario.
   *
   * @param usuarioService o servico de usuario
   */
  public UsuarioController(CadastrarUsuario usuarioService) {
    this.usuarioService = usuarioService;
  }

  /**
   * Cadastra um usuario novo.
   *
   * @param request dados do usuario a ser cadastrado
   * @return o identificador publico atribuido ao usuario, com status 201 Created
   */
  @PostMapping
  public ResponseEntity<UsuarioResponse> cadastrar(@Valid @RequestBody UsuarioRequest request) {
    String uuid = usuarioService.cadastrar(request.nome(), request.email(), request.senha());
    return ResponseEntity.status(HttpStatus.CREATED).body(new UsuarioResponse(uuid));
  }
}
