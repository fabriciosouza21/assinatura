package com.globo.assinatura.assinatura;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globo.assinatura.security.UsuarioAutenticado;
import com.globo.assinatura.web.ProblemExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Testes slice do {@link AssinaturaController}, cobrindo a derivacao do dono a partir do principal
 * autenticado e o repasse das excecoes de dominio para os status HTTP correspondentes.
 *
 * <p>Cada requisicao carrega um principal ja autenticado; a rejeicao de requisicoes sem token e
 * responsabilidade da cadeia real de filtros, coberta em {@code SolicitaAssinaturaSemTokenTest}.
 */
@WebMvcTest(
    controllers = AssinaturaController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.security\\..*"))
@Import({AssinaturaExceptionHandler.class, ProblemExceptionHandler.class})
class AssinaturaControllerTest {

  private static final String USUARIO_UUID = "550e8400-e29b-41d4-a716-446655440000";

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private SolicitarAssinatura solicitarAssinatura;

  @MockitoBean private ConsultarAssinatura consultarAssinatura;

  private static Authentication cliente(String usuarioId) {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", usuarioId, "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  private static Authentication admin() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("admin", null, UsuarioAutenticado.ROLE_ADMIN);
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  @Test
  @DisplayName("Deve solicitar assinatura para o usuario do token e retornar 202 com id e status")
  void deveSolicitarAssinaturaParaUsuarioDoTokenRetornandoAccepted() throws Exception {
    Assinatura assinatura = new Assinatura(1L, Plano.PREMIUM);
    when(solicitarAssinatura.executar(USUARIO_UUID, Plano.PREMIUM)).thenReturn(assinatura);
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(Plano.PREMIUM));

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(assinatura.getUuid()))
        .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
  }

  @Test
  @DisplayName("Deve ignorar usuarioId enviado no corpo e usar o usuario do token")
  void deveIgnorarUsuarioIdDoCorpoUsandoUsuarioDoToken() throws Exception {
    Assinatura assinatura = new Assinatura(1L, Plano.BASICO);
    when(solicitarAssinatura.executar(USUARIO_UUID, Plano.BASICO)).thenReturn(assinatura);
    String corpo = "{\"usuarioId\":\"outro-usuario\",\"plano\":\"BASICO\"}";

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isAccepted());

    verify(solicitarAssinatura).executar(USUARIO_UUID, Plano.BASICO);
    verify(solicitarAssinatura, never()).executar("outro-usuario", Plano.BASICO);
  }

  @Test
  @DisplayName("Deve retornar 403 ao solicitar com token sem usuario de dominio ligado")
  void deveRetornarForbiddenAoSolicitarComTokenSemUsuario() throws Exception {
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(Plano.PREMIUM));

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(admin()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));

    verify(solicitarAssinatura, never()).executar(any(), any());
  }

  @Test
  @DisplayName("Deve retornar 404 ao solicitar para usuario inexistente")
  void deveRetornarNotFoundAoSolicitarParaUsuarioInexistente() throws Exception {
    when(solicitarAssinatura.executar(any(), any())).thenThrow(new UsuarioNaoEncontradoException());
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(Plano.BASICO));

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 409 ao solicitar para usuario com assinatura aberta")
  void deveRetornarConflictAoSolicitarParaUsuarioComAssinaturaAberta() throws Exception {
    when(solicitarAssinatura.executar(any(), any())).thenThrow(new AssinaturaAbertaException());
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(Plano.BASICO));

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isConflict())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve consultar assinatura por uuid e retornar 200")
  void deveConsultarAssinaturaPorUuidRetornandoOk() throws Exception {
    AssinaturaResponse resposta =
        new AssinaturaResponse(
            "assinatura-uuid",
            "usuario-uuid",
            Plano.PREMIUM,
            null,
            null,
            StatusAssinatura.AGUARDANDO_PAGAMENTO);
    when(consultarAssinatura.executar(any(), any())).thenReturn(resposta);

    mockMvc
        .perform(get("/assinaturas/assinatura-uuid").with(authentication(cliente(USUARIO_UUID))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("assinatura-uuid"))
        .andExpect(jsonPath("$.usuarioId").value("usuario-uuid"))
        .andExpect(jsonPath("$.plano").value("PREMIUM"))
        .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
  }

  @Test
  @DisplayName("Deve retornar 404 ao consultar assinatura inexistente")
  void deveRetornarNotFoundAoConsultarAssinaturaInexistente() throws Exception {
    when(consultarAssinatura.executar(any(), any()))
        .thenThrow(new AssinaturaNaoEncontradaException());

    mockMvc
        .perform(get("/assinaturas/uuid-inexistente").with(authentication(cliente(USUARIO_UUID))))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 403 ao consultar assinatura de outro usuario")
  void deveRetornarForbiddenAoConsultarAssinaturaDeOutroUsuario() throws Exception {
    when(consultarAssinatura.executar(any(), any())).thenThrow(new AcessoNegadoException());

    mockMvc
        .perform(get("/assinaturas/assinatura-alheia").with(authentication(cliente(USUARIO_UUID))))
        .andExpect(status().isForbidden())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 400 com problem ao omitir o plano")
  void deveRetornarBadRequestAoOmitirPlano() throws Exception {
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(null));

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("plano"));
  }

  @Test
  @DisplayName("Deve retornar 400 com problem ao enviar plano invalido")
  void deveRetornarBadRequestAoEnviarPlanoInvalido() throws Exception {
    String corpo = "{\"plano\":\"INVALIDO\"}";

    mockMvc
        .perform(
            post("/assinaturas")
                .with(authentication(cliente(USUARIO_UUID)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("plano"));
  }
}
