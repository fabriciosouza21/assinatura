package com.globo.assinatura.consulta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.consulta.api.AssinaturaResponse;
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

  @Test
  @DisplayName("Deve responder do cache sem consultar o banco em acerto de cache")
  void deveResponderDoCacheSemConsultarBanco() {
    AssinaturaResponse cacheada = criarResposta();
    when(assinaturaAtivaCache.recuperar(USUARIO_UUID)).thenReturn(Optional.of(cacheada));

    Optional<AssinaturaResponse> resposta = query.executar(USUARIO_UUID);

    assertThat(resposta).as("Resposta vem do cache").containsSame(cacheada);
    verify(usuarioRepository, never()).findByUuid(any());
    verify(assinaturaRepository, never()).findByUsuarioIdAndStatus(any(), any());
  }
}
