package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultarAssinaturaAtivaTest {

  private static final String USUARIO_UUID = "usuario-uuid";

  private static final Long USUARIO_INTERNO = 42L;

  @Mock private UsuarioRepository usuarioRepository;

  @Mock private AssinaturaRepository assinaturaRepository;

  @Mock private AssinaturaAtivaCache assinaturaAtivaCache;

  @InjectMocks private ConsultarAssinaturaAtiva query;

  private static AssinaturaResponse criarResposta() {
    return new AssinaturaResponse(
        "assinatura-id",
        USUARIO_UUID,
        Plano.PREMIUM,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 2, 1),
        StatusAssinatura.ATIVA,
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 2, 1),
        Instant.parse("2026-02-01T12:00:00Z"),
        true);
  }

  private static Usuario usuarioComId() {
    Usuario usuario = new Usuario("Fulano", "fulano@example.com");
    usuario.setId(USUARIO_INTERNO);
    return usuario;
  }

  @Test
  @DisplayName("Deve responder do cache sem consultar o banco em acerto de cache")
  void deveResponderDoCacheSemConsultarBanco() {
    AssinaturaResponse cacheada = criarResposta();
    when(assinaturaAtivaCache.recuperar(USUARIO_UUID))
        .thenReturn(Optional.of(Optional.of(cacheada)));

    Optional<AssinaturaResponse> resposta = query.executar(USUARIO_UUID);

    assertThat(resposta).as("Resposta vem do cache").containsSame(cacheada);
    verify(usuarioRepository, never()).findByUuid(any());
    verify(assinaturaRepository, never()).findByUsuarioIdAndStatus(any(), any());
  }

  @Test
  @DisplayName(
      "Deve responder vazio do cache sem consultar o banco quando a ausencia esta cacheada")
  void deveResponderVazioDoCacheSemConsultarBancoQuandoAusenciaCacheada() {
    when(assinaturaAtivaCache.recuperar(USUARIO_UUID)).thenReturn(Optional.of(Optional.empty()));

    Optional<AssinaturaResponse> resposta = query.executar(USUARIO_UUID);

    assertThat(resposta).as("Ausencia cacheada responde vazio").isEmpty();
    verify(usuarioRepository, never()).findByUuid(any());
    verify(assinaturaRepository, never()).findByUsuarioIdAndStatus(any(), any());
  }

  @Test
  @DisplayName("Deve consultar o banco e popular o cache em miss")
  void deveConsultarBancoQuandoCacheMiss() {
    Usuario usuario = usuarioComId();
    Assinatura assinatura = new Assinatura(USUARIO_INTERNO, Plano.PREMIUM);
    assinatura.ativar(
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), Instant.parse("2026-02-01T12:00:00Z"));
    when(assinaturaAtivaCache.recuperar(USUARIO_UUID)).thenReturn(Optional.empty());
    when(usuarioRepository.findByUuid(USUARIO_UUID)).thenReturn(Optional.of(usuario));
    when(assinaturaRepository.findByUsuarioIdAndStatus(USUARIO_INTERNO, StatusAssinatura.ATIVA))
        .thenReturn(Optional.of(assinatura));

    Optional<AssinaturaResponse> resposta = query.executar(USUARIO_UUID);

    assertThat(resposta).as("Assinatura ativa consultada do banco").isPresent();
    verify(assinaturaAtivaCache).popular(USUARIO_UUID, resposta);
  }

  @Test
  @DisplayName("Deve popular ausencia no cache quando o banco nao tem assinatura ativa")
  void devePopularAusenciaQuandoBancoNaoTemAssinaturaAtiva() {
    when(assinaturaAtivaCache.recuperar(USUARIO_UUID)).thenReturn(Optional.empty());
    when(usuarioRepository.findByUuid(USUARIO_UUID)).thenReturn(Optional.of(usuarioComId()));
    when(assinaturaRepository.findByUsuarioIdAndStatus(USUARIO_INTERNO, StatusAssinatura.ATIVA))
        .thenReturn(Optional.empty());

    Optional<AssinaturaResponse> resposta = query.executar(USUARIO_UUID);

    assertThat(resposta).as("Sem assinatura ativa responde vazio").isEmpty();
    verify(assinaturaAtivaCache).popular(USUARIO_UUID, resposta);
  }
}
