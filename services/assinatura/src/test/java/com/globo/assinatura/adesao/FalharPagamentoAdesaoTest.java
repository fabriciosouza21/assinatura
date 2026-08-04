package com.globo.assinatura.adesao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.adesao.idempotencia.PagamentoEventoProcessadoRepository;
import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.shared.cache.CacheVersionado;
import com.globo.assinatura.shared.contrato.AssinaturaAdesaoEsgotada;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Teste unitario do {@link FalharPagamentoAdesao}.
 *
 * <p>Verifica a transicao da assinatura para {@link StatusAssinatura#PAGAMENTO_FALHOU} sob lock
 * pessimista e a idempotencia por {@code eventId}.
 */
@ExtendWith(MockitoExtension.class)
class FalharPagamentoAdesaoTest {

  @Mock private AssinaturaRepository assinaturaRepository;
  @Mock private PagamentoEventoProcessadoRepository pagamentoEventoProcessadoRepository;
  @Mock private UsuarioRepository usuarioRepository;
  @Mock private CacheVersionado cacheVersionado;

  private Clock relogio;
  private FalharPagamentoAdesao command;

  @BeforeEach
  void setUp() {
    relogio = Clock.systemDefaultZone();
    command =
        new FalharPagamentoAdesao(
            assinaturaRepository,
            pagamentoEventoProcessadoRepository,
            usuarioRepository,
            cacheVersionado,
            relogio);
  }

  @Test
  @DisplayName("Deve transitar a assinatura para pagamento falhou ao receber esgotamento")
  void deveTransitarAssinaturaParaPagamentoFalhouAoReceberEsgotamento() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    UUID eventId = UUID.randomUUID();
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(false);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));

    command.executar(
        new AssinaturaAdesaoEsgotada(
            eventId, Instant.now(), UUID.fromString(assinatura.getUuid())));

    assertThat(assinatura.getStatus())
        .as("Status apos esgotamento")
        .isEqualTo(StatusAssinatura.PAGAMENTO_FALHOU);
    verify(pagamentoEventoProcessadoRepository).save(any());
  }

  @Test
  @DisplayName("Nao deve transitar quando o evento ja foi processado")
  void naoDeveTransitarQuandoEventoJaProcessado() {
    UUID eventId = UUID.randomUUID();
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(true);

    command.executar(new AssinaturaAdesaoEsgotada(eventId, Instant.now(), UUID.randomUUID()));

    verify(assinaturaRepository, never()).buscarPorUuidParaAtualizacao(any());
    verify(pagamentoEventoProcessadoRepository, never()).save(any());
  }

  @Test
  @DisplayName("Deve ignorar silenciosamente assinatura inexistente")
  void deveIgnorarSilenciosamenteAssinaturaInexistente() {
    UUID assinaturaId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    when(pagamentoEventoProcessadoRepository.existsByEventId(eventId)).thenReturn(false);
    when(assinaturaRepository.buscarPorUuidParaAtualizacao(assinaturaId.toString()))
        .thenReturn(Optional.empty());

    command.executar(new AssinaturaAdesaoEsgotada(eventId, Instant.now(), assinaturaId));

    verify(pagamentoEventoProcessadoRepository, never()).save(any());
  }
}
