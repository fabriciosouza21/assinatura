package com.globo.assinatura.usuario;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes slice do {@link UsuarioController}, isolando o comportamento do controller do resto da
 * aplicacao (incluindo a cadeia de filtros do Spring Security).
 *
 * <p>O {@code excludeFilter} por regex impede que o {@code SecurityConfig} de producao (pacote
 * {@code com.globo.assinatura.security}) seja carregado no slice. Porem, a autoconfiguracao padrao
 * de seguranca servlet do Spring Boot ({@link ServletWebSecurityAutoConfiguration}) ainda instala
 * uma cadeia de filtros default com protecao CSRF, que rejeita o POST com 403. Excluir apenas essa
 * autoconfiguracao remove a cadeia default e deixa a requisicao chegar ao controller. A prova de
 * que {@code /usuarios} e acessivel anonimamente num contexto completo e feita num ciclo posterior
 * com {@code @SpringBootTest}.
 */
@WebMvcTest(
    controllers = UsuarioController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.security\\..*"))
@ImportAutoConfiguration(exclude = {ServletWebSecurityAutoConfiguration.class})
@Import(EmailJaCadastradoExceptionHandler.class)
class UsuarioControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UsuarioService usuarioService;

  @Test
  @DisplayName("Deve cadastrar usuario valido e retornar 201 com o uuid")
  void cadastraUsuarioRetornaCreatedComId() throws Exception {
    when(usuarioService.cadastrar("Fulano", "novo@example.com")).thenReturn("uuid-do-usuario");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Fulano\",\"email\":\"novo@example.com\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("uuid-do-usuario"));
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com nome vazio retornando 400")
  void cadastraUsuarioComNomeVazioRetornaBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"\",\"email\":\"valido@example.com\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com email invalido retornando 400")
  void cadastraUsuarioComEmailInvalidoRetornaBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Fulano\",\"email\":\"nao-e-um-email\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com email ja cadastrado retornando 409")
  void cadastraUsuarioComEmailExistenteRetornaConflict() throws Exception {
    when(usuarioService.cadastrar("Fulano", "existente@example.com"))
        .thenThrow(new EmailJaCadastradoException("existente@example.com"));

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Fulano\",\"email\":\"existente@example.com\"}"))
        .andExpect(status().isConflict());
  }
}
