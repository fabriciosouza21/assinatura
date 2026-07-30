package com.globo.assinatura.assinatura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.globo.assinatura.messaging.event.PagamentoStatusAtualizado;
import com.globo.assinatura.messaging.event.StatusPagamento;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessarPagamentoTest {

  @Mock private AssinaturaRepository assinaturaRepository;

  @InjectMocks private ProcessarPagamento command;

  @Test
  @DisplayName(
      "Deve carregar a assinatura sob lock e transita-la para ativa quando o pagamento e aprovado")
  void deveAtivarAssinaturaQuandoPagamentoAprovado() {
    Assinatura assinatura = new Assinatura(42L, Plano.PREMIUM);
    when(assinaturaRepository.findByUuidForUpdate(assinatura.getUuid()))
        .thenReturn(Optional.of(assinatura));
    PagamentoStatusAtualizado evento =
        new PagamentoStatusAtualizado(
            UUID.randomUUID(),
            Instant.now(),
            UUID.fromString(assinatura.getUuid()),
            StatusPagamento.APPROVED,
            UUID.randomUUID());

    command.executar(evento);

    verify(assinaturaRepository).findByUuidForUpdate(assinatura.getUuid());
    assertThat(assinatura.getStatus())
        .as("assinatura aprovada deve transitar para ativa")
        .isEqualTo(StatusAssinatura.ATIVA);
  }
}
