package com.globo.assinatura.shared.outbox.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.globo.assinatura.shared.outbox.ListarFalhasOutbox;
import com.globo.assinatura.shared.outbox.OutboxEvent;
import com.globo.assinatura.shared.outbox.OutboxEventoNaoEncontradoException;
import com.globo.assinatura.shared.outbox.OutboxEventoNaoRetomavelException;
import com.globo.assinatura.shared.outbox.OutboxStatus;
import com.globo.assinatura.shared.outbox.RetomarEventoOutbox;
import com.globo.assinatura.shared.seguranca.UsuarioAutenticado;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

/** Testes HTTP da recuperacao manual assistida da outbox. */
@WebMvcTest(
    controllers = OutboxFalhaController.class,
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.globo\\.assinatura\\.shared\\.seguranca\\..*"))
@Import(OutboxFalhaExceptionHandler.class)
class OutboxFalhaControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ListarFalhasOutbox listarFalhasOutbox;
  @MockitoBean private RetomarEventoOutbox retomarEventoOutbox;

  private static Authentication administrador() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("admin", null, UsuarioAutenticado.ROLE_ADMIN);
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  private static Authentication cliente() {
    UsuarioAutenticado principal =
        new UsuarioAutenticado("cliente@example.com", "usuario-uuid", "ROLE_CLIENT");
    return new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority(principal.role())));
  }

  @Test
  @DisplayName("Deve listar eventos em falha para administrador com filtros e paginacao")
  void deveListarEventosEmFalhaParaAdministrador() throws Exception {
    OutboxFalhaItem item =
        new OutboxFalhaItem(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "AssinaturaSolicitada",
            Instant.parse("2026-08-03T10:00:00Z"),
            3);
    when(listarFalhasOutbox.executar("AssinaturaSolicitada", 3600L, 0, 20))
        .thenReturn(new OutboxFalhaLista(List.of(item), 0, 20, 1));

    mockMvc
        .perform(
            get("/outbox/falhas")
                .param("tipoEvento", "AssinaturaSolicitada")
                .param("falhouHaSegundos", "3600")
                .with(authentication(administrador()))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].eventId").value("11111111-1111-1111-1111-111111111111"))
        .andExpect(jsonPath("$.items[0].eventType").value("AssinaturaSolicitada"))
        .andExpect(jsonPath("$.items[0].falhouEm").value("2026-08-03T10:00:00Z"))
        .andExpect(jsonPath("$.items[0].ciclosRecuperacao").value(3))
        .andExpect(jsonPath("$.total").value(1));

    verify(listarFalhasOutbox).executar("AssinaturaSolicitada", 3600L, 0, 20);
  }

  @Test
  @DisplayName("Deve retornar 403 para cliente na listagem de eventos em falha")
  void deveRetornarForbiddenParaClienteNaListagem() throws Exception {
    mockMvc
        .perform(get("/outbox/falhas").with(authentication(cliente())).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Deve limitar falhou ha, pagina e tamanho na listagem")
  void deveLimitarFalhouHaPaginaTamanhoNaListagem() throws Exception {
    mockMvc
        .perform(
            get("/outbox/falhas")
                .param("falhouHaSegundos", "9223372036854775807")
                .param("page", "2147483647")
                .param("size", "500")
                .with(authentication(administrador()))
                .with(csrf()))
        .andExpect(status().isOk());

    verify(listarFalhasOutbox).executar(null, 31_536_000L, 10_000, 100);
  }

  @Test
  @DisplayName("Deve retomar evento em falha para administrador")
  void deveRetomarEventoParaAdministrador() throws Exception {
    OutboxEvent evento = eventoRetomado();
    when(retomarEventoOutbox.executar(evento.getEventId(), "admin")).thenReturn(evento);

    mockMvc
        .perform(
            post("/outbox/falhas/{eventId}/retomada", evento.getEventId())
                .with(authentication(administrador()))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(evento.getEventId().toString()))
        .andExpect(jsonPath("$.status").value("RETENTATIVA_DLQ"));
  }

  @Test
  @DisplayName("Deve retornar 404 na retomada de evento inexistente")
  void deveRetornarNotFoundNaRetomadaDeEventoInexistente() throws Exception {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(retomarEventoOutbox.executar(eventId, "admin"))
        .thenThrow(new OutboxEventoNaoEncontradoException(eventId));

    mockMvc
        .perform(
            post("/outbox/falhas/{eventId}/retomada", eventId)
                .with(authentication(administrador()))
                .with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Deve retornar 409 na retomada de evento fora de falha")
  void deveRetornarConflictNaRetomadaDeEventoForaDeFalha() throws Exception {
    UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    when(retomarEventoOutbox.executar(eventId, "admin"))
        .thenThrow(new OutboxEventoNaoRetomavelException(eventId, OutboxStatus.PUBLICADO));

    mockMvc
        .perform(
            post("/outbox/falhas/{eventId}/retomada", eventId)
                .with(authentication(administrador()))
                .with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Deve retornar 403 para cliente na retomada")
  void deveRetornarForbiddenParaClienteNaRetomada() throws Exception {
    mockMvc
        .perform(
            post("/outbox/falhas/{eventId}/retomada", UUID.randomUUID())
                .with(authentication(cliente()))
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  private static OutboxEvent eventoRetomado() {
    OutboxEvent evento =
        OutboxEvent.criar(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "Assinatura",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "AssinaturaSolicitada",
            "{}");
    evento.marcarFalha("timeout", Instant.parse("2026-08-03T10:00:00Z"));
    evento.recuperarParaRetentativa(Instant.parse("2026-08-03T11:00:00Z"));
    return evento;
  }
}
