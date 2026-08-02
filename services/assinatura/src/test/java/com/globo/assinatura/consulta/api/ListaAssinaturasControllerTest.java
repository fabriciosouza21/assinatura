package com.globo.assinatura.consulta.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.assinatura.consulta.ListarAssinaturas;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Testes HTTP da listagem de assinaturas. */
@WebMvcTest(
    controllers = ListaAssinaturasController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@Import(ConsultaExceptionHandler.class)
class ListaAssinaturasControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ListarAssinaturas listarAssinaturas;

  private static Authentication cliente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", "usuario-uuid", "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  private static Authentication administrador() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("admin", null, UsuarioAutenticado.ROLE_ADMIN);
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  private static AssinaturaLista paginaVazia(int page, int size) {
    return new AssinaturaLista(List.of(), page, size, 0);
  }

  @Test
  @DisplayName("Deve listar assinaturas com pagina e tamanho padrao")
  void deveListarAssinaturasComPaginaPadrao() throws Exception {
    when(listarAssinaturas.executar("usuario-uuid", 0, 20)).thenReturn(paginaVazia(0, 20));

    mockMvc
        .perform(get("/assinaturas").with(authentication(cliente())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items").isArray());

    verify(listarAssinaturas).executar("usuario-uuid", 0, 20);
  }

  @Test
  @DisplayName("Deve listar assinaturas com pagina e tamanho informados")
  void deveListarAssinaturasComParametrosInformados() throws Exception {
    when(listarAssinaturas.executar("usuario-uuid", 2, 10)).thenReturn(paginaVazia(2, 10));

    mockMvc
        .perform(
            get("/assinaturas")
                .param("page", "2")
                .param("size", "10")
                .with(authentication(cliente())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.size").value(10));

    verify(listarAssinaturas).executar("usuario-uuid", 2, 10);
  }

  @Test
  @DisplayName("Deve limitar o tamanho da pagina ao maximo de 100")
  void deveLimitarTamanhoDaPaginaAoMaximo() throws Exception {
    when(listarAssinaturas.executar("usuario-uuid", 0, 100)).thenReturn(paginaVazia(0, 100));

    mockMvc
        .perform(get("/assinaturas").param("size", "1000").with(authentication(cliente())))
        .andExpect(status().isOk());

    verify(listarAssinaturas).executar("usuario-uuid", 0, 100);
  }

  @Test
  @DisplayName("Deve retornar 403 para administrador sem usuario de dominio")
  void deveRetornarForbiddenParaAdministrador() throws Exception {
    mockMvc
        .perform(get("/assinaturas").with(authentication(administrador())))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));

    verify(listarAssinaturas, never())
        .executar(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
  }
}
