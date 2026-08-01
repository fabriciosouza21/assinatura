package com.globo.assinatura.assinatura;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globo.assinatura.web.ProblemExceptionHandler;
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
 * Testes slice do {@link AssinaturaController}, isolando o comportamento do controller da cadeia de
 * filtros do Spring Security.
 */
@WebMvcTest(
    controllers = AssinaturaController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.security\\..*"))
@ImportAutoConfiguration(exclude = {ServletWebSecurityAutoConfiguration.class})
@Import({AssinaturaExceptionHandler.class, ProblemExceptionHandler.class})
class AssinaturaControllerTest {

  @Autowired private MockMvc mockMvc;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @MockitoBean private SolicitarAssinatura solicitarAssinatura;

  @MockitoBean private ConsultarAssinatura consultarAssinatura;

  @Test
  @DisplayName("Deve solicitar assinatura valida e retornar 202 com id e status")
  void deveSolicitarAssinaturaValidaRetornandoAccepted() throws Exception {
    Assinatura assinatura = new Assinatura(1L, Plano.PREMIUM);
    when(solicitarAssinatura.executar("550e8400-e29b-41d4-a716-446655440000", Plano.PREMIUM))
        .thenReturn(assinatura);
    String corpo =
        objectMapper.writeValueAsString(
            new AssinaturaRequest("550e8400-e29b-41d4-a716-446655440000", Plano.PREMIUM));

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(assinatura.getUuid()))
        .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
  }

  @Test
  @DisplayName("Deve retornar 404 ao solicitar para usuario inexistente")
  void deveRetornarNotFoundAoSolicitarParaUsuarioInexistente() throws Exception {
    when(solicitarAssinatura.executar(any(), any())).thenThrow(new UsuarioNaoEncontradoException());
    String corpo =
        objectMapper.writeValueAsString(
            new AssinaturaRequest("550e8400-e29b-41d4-a716-446655440000", Plano.BASICO));

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 409 ao solicitar para usuario com assinatura aberta")
  void deveRetornarConflictAoSolicitarParaUsuarioComAssinaturaAberta() throws Exception {
    when(solicitarAssinatura.executar(any(), any())).thenThrow(new AssinaturaAbertaException());
    String corpo =
        objectMapper.writeValueAsString(
            new AssinaturaRequest("550e8400-e29b-41d4-a716-446655440000", Plano.BASICO));

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
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
    when(consultarAssinatura.executar("assinatura-uuid")).thenReturn(resposta);

    mockMvc
        .perform(get("/assinaturas/assinatura-uuid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("assinatura-uuid"))
        .andExpect(jsonPath("$.usuarioId").value("usuario-uuid"))
        .andExpect(jsonPath("$.plano").value("PREMIUM"))
        .andExpect(jsonPath("$.status").value("AGUARDANDO_PAGAMENTO"));
  }

  @Test
  @DisplayName("Deve retornar 404 ao consultar assinatura inexistente")
  void deveRetornarNotFoundAoConsultarAssinaturaInexistente() throws Exception {
    when(consultarAssinatura.executar("uuid-inexistente"))
        .thenThrow(new AssinaturaNaoEncontradaException());

    mockMvc
        .perform(get("/assinaturas/uuid-inexistente"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));
  }

  @Test
  @DisplayName("Deve retornar 400 com problem ao omitir usuarioId")
  void deveRetornarBadRequestAoOmitirUsuarioId() throws Exception {
    String corpo = objectMapper.writeValueAsString(new AssinaturaRequest(null, Plano.PREMIUM));

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("usuarioId"));
  }

  @Test
  @DisplayName("Deve retornar 400 com problem ao enviar usuarioId com formato invalido")
  void deveRetornarBadRequestAoEnviarUsuarioIdComFormatoInvalido() throws Exception {
    String corpo =
        objectMapper.writeValueAsString(new AssinaturaRequest("nao-e-um-uuid", Plano.PREMIUM));

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("usuarioId"));
  }

  @Test
  @DisplayName("Deve retornar 400 com problem ao enviar plano invalido")
  void deveRetornarBadRequestAoEnviarPlanoInvalido() throws Exception {
    String corpo =
        "{\"usuarioId\":\"550e8400-e29b-41d4-a716-446655440000\",\"plano\":\"INVALIDO\"}";

    mockMvc
        .perform(post("/assinaturas").contentType(MediaType.APPLICATION_JSON).content(corpo))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisicao invalida"))
        .andExpect(jsonPath("$.errors[0].campo").value("plano"));
  }
}
