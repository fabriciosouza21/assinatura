package com.globo.assinatura.usuario;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private UsuarioService usuarioService;

  @Test
  @DisplayName("Deve cadastrar usuario valido e retornar 201 com o uuid")
  void cadastraUsuarioRetornaCreatedComId() throws Exception {
    UsuarioRequest request = new UsuarioRequest("Fulano", "novo@example.com", "SenhaForte1");
    when(usuarioService.cadastrar("Fulano", "novo@example.com", "SenhaForte1"))
        .thenReturn("uuid-do-usuario");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("uuid-do-usuario"));
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com nome vazio retornando 400")
  void cadastraUsuarioComNomeVazioRetornaBadRequest() throws Exception {
    UsuarioRequest request = new UsuarioRequest("", "valido@example.com", "SenhaForte1");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com email invalido retornando 400")
  void cadastraUsuarioComEmailInvalidoRetornaBadRequest() throws Exception {
    UsuarioRequest request = new UsuarioRequest("Fulano", "nao-e-um-email", "SenhaForte1");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com email ja cadastrado retornando 409")
  void cadastraUsuarioComEmailExistenteRetornaConflict() throws Exception {
    UsuarioRequest request = new UsuarioRequest("Fulano", "existente@example.com", "SenhaForte1");
    when(usuarioService.cadastrar("Fulano", "existente@example.com", "SenhaForte1"))
        .thenThrow(new EmailJaCadastradoException());

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com email ausente retornando 400")
  void cadastraUsuarioComEmailAusenteRetornaBadRequest() throws Exception {
    UsuarioRequest request = new UsuarioRequest("Fulano", null, "SenhaForte1");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Deve rejeitar cadastro com senha curta retornando 400")
  void cadastraUsuarioComSenhaCurtaRetornaBadRequest() throws Exception {
    UsuarioRequest request = new UsuarioRequest("Fulano", "valido@example.com", "123");

    mockMvc
        .perform(
            post("/usuarios")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }
}
