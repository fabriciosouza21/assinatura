package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.globo.assinatura.messaging.event.PagamentoRenovacaoAprovado;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessarRenovacaoResultadoTest {

  @Mock private RenovacaoRepository renovacaoRepository;
  @Mock private AssinaturaRepository assinaturaRepository;

  private ProcessarRenovacaoResultado command;

  @BeforeEach
  void setUp() {
    command = new ProcessarRenovacaoResultado(renovacaoRepository, assinaturaRepository);
  }

  @Test
  @DisplayName("Deve voltar a assinatura para ativa ao aprovar a renovacao")
  void deveVoltarAssinaturaParaAtivaAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);

    command.executar(evento);

    assertThat(assinatura.getStatus())
        .as("assinatura deve voltar a ATIVA após renovação aprovada")
        .isEqualTo(StatusAssinatura.ATIVA);
  }

  @Test
  @DisplayName("Deve avancar o fim do ciclo em um mes ao aprovar a renovacao")
  void deveAvancarFimCicloEmUmMesAoAprovarRenovacao() {
    Assinatura assinatura = new Assinatura(7L, Plano.PREMIUM);
    assinatura.ativar(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15));
    assinatura.iniciarRenovacao();
    Renovacao renovacao = new Renovacao(42L, assinatura.getFimCiclo(), 2);
    when(renovacaoRepository.buscarPorUuidParaAtualizacao(renovacao.getUuid()))
        .thenReturn(Optional.of(renovacao));
    when(assinaturaRepository.findById(42L)).thenReturn(Optional.of(assinatura));
    PagamentoRenovacaoAprovado evento =
        new PagamentoRenovacaoAprovado(
            UUID.randomUUID(),
            Instant.parse("2026-02-15T12:00:00Z"),
            UUID.fromString(renovacao.getUuid()),
            UUID.randomUUID(),
            "pay_123",
            2);
    LocalDate fimCicloAntes = assinatura.getFimCiclo();

    command.executar(evento);

    assertThat(assinatura.getFimCiclo())
        .as("novo fim de ciclo deve avancar um mes sobre o anterior")
        .isEqualTo(fimCicloAntes.plusMonths(1));
  }
}
