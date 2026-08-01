package com.globo.assinatura.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import com.globo.assinatura.assinatura.Assinatura;
import com.globo.assinatura.assinatura.AssinaturaRepository;
import com.globo.assinatura.assinatura.Plano;
import com.globo.assinatura.assinatura.RenovacaoRepository;
import com.globo.assinatura.assinatura.StatusAssinatura;
import com.globo.assinatura.outbox.OutboxEvent;
import com.globo.assinatura.outbox.OutboxRepository;
import com.globo.assinatura.usuario.Usuario;
import com.globo.assinatura.usuario.UsuarioRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de integracao do {@link RenovacaoScheduler} contra o Postgres real.
 *
 * <p>Valida ponta a ponta contra o schema Flyway: a query {@code FOR UPDATE SKIP LOCKED} seleciona
 * a assinatura vencida, o scheduler cria a renovacao, transita para {@code EM_RENOVACAO} e grava
 * {@code RenovacaoSolicitada} na outbox na mesma transacao; a segunda execucao e idempotente; e o
 * opt-out cancela a assinatura. Usa o Postgres do docker-compose (porta 5433).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/assinatura",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class RenovacaoSchedulerIntegracaoTest {

  @Autowired private AssinaturaRepository assinaturaRepository;
  @Autowired private RenovacaoRepository renovacaoRepository;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private UsuarioRepository usuarioRepository;

  @Test
  @DisplayName("Deve renovar assinatura vencida criando renovacao e evento na outbox")
  void deveRenovarAssinaturaVencidaCriandoRenovacaoEventoOutbox() {
    Assinatura assinatura = persistirAssinaturaAtivaVencida(true);
    RenovacaoScheduler scheduler = novoScheduler();

    scheduler.varrerVencimentos();

    Assinatura recarregada = assinaturaRepository.findById(assinatura.getId()).orElseThrow();
    assertThat(recarregada.getStatus())
        .as("Assinatura em renovacao")
        .isEqualTo(StatusAssinatura.EM_RENOVACAO);

    List<OutboxEvent> eventos = outboxRepository.findAll();
    assertThat(eventos)
        .as("Evento RenovacaoSolicitada gravado na outbox")
        .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo("RenovacaoSolicitada"));
  }

  @Test
  @DisplayName("Segunda execucao nao deve duplicar renovacao nem evento")
  void segundaExecucaoNaoDeveDuplicar() {
    Assinatura assinatura = persistirAssinaturaAtivaVencida(true);
    RenovacaoScheduler scheduler = novoScheduler();

    scheduler.varrerVencimentos();
    long renovacoesAposPrimeira = renovacaoRepository.count();
    long eventosAposPrimeira =
        outboxRepository.findAll().stream()
            .filter(e -> "RenovacaoSolicitada".equals(e.getEventType()))
            .count();

    scheduler.varrerVencimentos();

    assertThat(renovacaoRepository.count())
        .as("Renovacoes nao duplicadas")
        .isEqualTo(renovacoesAposPrimeira);
    long eventosAposSegunda =
        outboxRepository.findAll().stream()
            .filter(e -> "RenovacaoSolicitada".equals(e.getEventType()))
            .count();
    assertThat(eventosAposSegunda).as("Eventos nao duplicados").isEqualTo(eventosAposPrimeira);
  }

  @Test
  @DisplayName("Deve cancelar assinatura com opt-out no vencimento")
  void deveCancelarAssinaturaComOptOut() {
    Assinatura assinatura = persistirAssinaturaAtivaVencida(false);
    RenovacaoScheduler scheduler = novoScheduler();

    scheduler.varrerVencimentos();

    Assinatura recarregada = assinaturaRepository.findById(assinatura.getId()).orElseThrow();
    assertThat(recarregada.getStatus())
        .as("Assinatura cancelada por opt-out")
        .isEqualTo(StatusAssinatura.CANCELADA);
    assertThat(renovacaoRepository.countByAssinaturaId(assinatura.getId()))
        .as("Nenhuma renovacao criada para opt-out")
        .isZero();
  }

  private RenovacaoScheduler novoScheduler() {
    return new RenovacaoScheduler(
        assinaturaRepository,
        renovacaoRepository,
        outboxRepository,
        JsonMapper.builder().build(),
        100,
        Clock.systemUTC());
  }

  private Assinatura persistirAssinaturaAtivaVencida(boolean renovacaoAutomatica) {
    // Email unico por execucao para nao conflitar com os demais testes de integracao que
    // compartilham a base sem cleanup.
    Usuario usuario =
        usuarioRepository.save(
            new Usuario("Fulano", "scheduler-" + UUID.randomUUID() + "@example.com"));
    Assinatura assinatura = new Assinatura(usuario.getId(), Plano.PREMIUM);
    LocalDate inicio = LocalDate.now().minusDays(60);
    LocalDate vencimento = LocalDate.now().minusDays(30);
    assinatura.ativar(inicio, vencimento);
    if (!renovacaoAutomatica) {
      desabilitarRenovacaoAutomatica(assinatura);
    }
    return assinaturaRepository.saveAndFlush(assinatura);
  }

  private static void desabilitarRenovacaoAutomatica(Assinatura assinatura) {
    try {
      Field campo = Assinatura.class.getDeclaredField("renovacaoAutomatica");
      campo.setAccessible(true);
      campo.setBoolean(assinatura, false);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Falha ao ajustar renovacaoAutomatica no teste", e);
    }
  }
}
