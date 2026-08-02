package com.globo.assinatura.adesao.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globo.assinatura.adesao.AssinaturaAbertaException;
import com.globo.assinatura.adesao.SolicitarAssinatura;
import com.globo.assinatura.adesao.UsuarioNaoEncontradoException;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import com.globo.assinatura.shared.web.ProblemExceptionHandler;
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
 * Testes slice do {@link AdesaoController}, cobrindo a derivacao do dono a partir do principal
 * autenticado e o repasse das excecoes de dominio para os status HTTP correspondentes.
 *
 * <p>Cada requisicao carrega um principal ja autenticado; a rejeicao de requisicoes sem token e
 * responsabilidade da cadeia real de filtros, coberta em {@code SolicitaAssinaturaSemTokenTest}.
 */
@WebMvcTest(
    controllers = AdesaoController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@Import({AdesaoExceptionHandler.class, ProblemExceptionHandler.class})
class AdesaoControllerTest {

  private static final String USUARIO_UUID = "550e8400-e29b-41d4-a716-446655440000";

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private SolicitarAssinatura solicitarAssinatura;

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
