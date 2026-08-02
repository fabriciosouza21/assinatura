package com.globo.pagamento.renovacao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teste de integracao do {@link TentativaCobrancaRepository} contra o Postgres real.
 *
 * <p>Valida o predicado de elegibilidade do scheduler: a query {@code buscarProntasParaCobrar()}
 * deve devolver apenas as tentativas {@code PENDENTE} que ainda nao possuem {@code paymentId} e
 * cujo agendamento venceu, excluindo as ja cobradas pelo gateway, as ja decididas pelo webhook e as
 * de retry marcadas para o futuro. Usa o Postgres do docker-compose (porta 5433, banco {@code
 * pagamento}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Tag("integration")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5433/pagamento",
      "spring.datasource.username=assinatura",
      "spring.datasource.password=assinatura",
    })
class TentativaCobrancaRepositoryTest {

  @Autowired private TentativaCobrancaRepository tentativaCobrancaRepository;
  @Autowired private EntityManagerFactory emf;

  private EntityManager emSetup;
  private EntityManager emT1;
  private EntityManager emT2;

  @Test
  @DisplayName("Deve retornar apenas as tentativas pendentes sem payment id")
  void deveRetornarApenasTentativasSemPaymentId() {
    TentativaCobranca pronta = new TentativaCobranca("ren-A", 1);
    TentativaCobranca cobrada = new TentativaCobranca("ren-B", 1);
    cobrada.registrarCobranca("pay-1");
    tentativaCobrancaRepository.save(pronta);
    tentativaCobrancaRepository.save(cobrada);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Apenas a tentativa sem payment id e elegivel")
        .extracting(TentativaCobranca::getRenovacaoId)
        .containsExactly("ren-A");
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa de retry antes da data agendada")
  void naoDeveRetornarTentativaDeRetryAntesDaDataAgendada() {
    TentativaCobranca agendadaNoFuturo = new TentativaCobranca("ren-futuro", 2);
    agendadaNoFuturo.agendarPara(Instant.now().plus(3, ChronoUnit.DAYS));
    tentativaCobrancaRepository.save(agendadaNoFuturo);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas).as("Backoff mantem a tentativa fora do lote ate a data marcada").isEmpty();
  }

  @Test
  @DisplayName("Deve retornar a tentativa de retry quando a data agendada ja venceu")
  void deveRetornarTentativaDeRetryQuandoDataAgendadaVenceu() {
    TentativaCobranca vencida = new TentativaCobranca("ren-vencida", 2);
    vencida.agendarPara(Instant.now().minus(1, ChronoUnit.MINUTES));
    tentativaCobrancaRepository.save(vencida);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Tentativa agendada e elegivel assim que a data vence")
        .extracting(TentativaCobranca::getRenovacaoId)
        .containsExactly("ren-vencida");
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa ja decidida pelo webhook")
  void naoDeveRetornarTentativaJaDecidida() {
    TentativaCobranca recusada = new TentativaCobranca("ren-recusada", 1);
    recusada.recusar();
    tentativaCobrancaRepository.save(recusada);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas).as("Tentativa fora de PENDENTE nao volta para o lote").isEmpty();
  }

  @Test
  @DisplayName("Deve limitar o lote selecionado em um unico ciclo")
  void deveLimitarLoteSelecionadoEmUmUnicoCiclo() {
    for (int i = 0; i < 205; i++) {
      tentativaCobrancaRepository.save(new TentativaCobranca("ren-lote-" + i, 1));
    }
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> prontas = tentativaCobrancaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Lote limitado para nao segurar a transacao do scheduler por tentativas demais")
        .hasSize(200);
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa travada por transacao concorrente")
  @Transactional(propagation = Propagation.NEVER)
  void naoDeveRetornarTentativaTravadaPorTransacaoConcorrente() {
    emSetup = emf.createEntityManager();
    emT1 = emf.createEntityManager();
    emT2 = emf.createEntityManager();
    try {
      persistirTentativaProntaCommitada();

      TentativaCobrancaRepository repoT1 =
          new JpaRepositoryFactory(emT1).getRepository(TentativaCobrancaRepository.class);

      emT1.getTransaction().begin();
      repoT1.buscarProntasParaCobrar();

      TentativaCobrancaRepository repoT2 =
          new JpaRepositoryFactory(emT2).getRepository(TentativaCobrancaRepository.class);
      emT2.getTransaction().begin();
      emT2.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
      List<TentativaCobranca> resultadoT2 = repoT2.buscarProntasParaCobrar();

      assertThat(resultadoT2).as("T2 nao deve ver a tentativa travada por T1").isEmpty();
    } finally {
      rollbackAtivo(emT1);
      rollbackAtivo(emT2);
    }
  }

  @Test
  @DisplayName("Deve retornar apenas as tentativas pendentes sem payment id da renovacao")
  void deveRetornarApenasTentativasPendentesSemPaymentIdDaRenovacao() {
    TentativaCobranca pendente = new TentativaCobranca("ren-filtro", 1);
    TentativaCobranca cobrada = new TentativaCobranca("ren-filtro", 2);
    cobrada.registrarCobranca("pay-filtro");
    TentativaCobranca recusada = new TentativaCobranca("ren-filtro", 3);
    recusada.recusar();
    TentativaCobranca outraRenovacao = new TentativaCobranca("ren-outra", 1);
    tentativaCobrancaRepository.save(pendente);
    tentativaCobrancaRepository.save(cobrada);
    tentativaCobrancaRepository.save(recusada);
    tentativaCobrancaRepository.save(outraRenovacao);
    tentativaCobrancaRepository.flush();

    List<TentativaCobranca> pendentes =
        tentativaCobrancaRepository.buscarPendentesPorRenovacaoId("ren-filtro");

    assertThat(pendentes)
        .as("Apenas a tentativa pendente sem payment id da renovacao")
        .extracting(TentativaCobranca::getNumero)
        .containsExactly(1);
  }

  @Test
  @DisplayName("Deve pular no cancelamento a tentativa travada pelo scheduler")
  @Transactional(propagation = Propagation.NEVER)
  void devePularNoCancelamentoTentativaTravadaPeloScheduler() {
    emSetup = emf.createEntityManager();
    emT1 = emf.createEntityManager();
    emT2 = emf.createEntityManager();
    try {
      persistirTentativaProntaCommitada();

      TentativaCobrancaRepository repoT1 =
          new JpaRepositoryFactory(emT1).getRepository(TentativaCobrancaRepository.class);
      emT1.getTransaction().begin();
      repoT1.buscarPendentesPorRenovacaoId("ren-lock");

      TentativaCobrancaRepository repoT2 =
          new JpaRepositoryFactory(emT2).getRepository(TentativaCobrancaRepository.class);
      emT2.getTransaction().begin();
      emT2.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
      List<TentativaCobranca> resultadoT2 = repoT2.buscarPendentesPorRenovacaoId("ren-lock");

      assertThat(resultadoT2).as("T2 nao deve bloquear na tentativa travada por T1").isEmpty();
    } finally {
      rollbackAtivo(emT1);
      rollbackAtivo(emT2);
    }
  }

  private void persistirTentativaProntaCommitada() {
    emSetup.getTransaction().begin();
    emSetup.persist(new TentativaCobranca("ren-lock", 1));
    emSetup.getTransaction().commit();
  }

  @AfterEach
  void fecharManagersLimparTabela() {
    rollbackAtivo(emT1);
    rollbackAtivo(emT2);
    rollbackAtivo(emSetup);
    fechar(emT1);
    fechar(emT2);
    fechar(emSetup);
    limparTentativas();
  }

  private void rollbackAtivo(EntityManager em) {
    if (em != null && em.isOpen() && em.getTransaction().isActive()) {
      em.getTransaction().rollback();
    }
  }

  private void fechar(EntityManager em) {
    if (em != null && em.isOpen()) {
      em.close();
    }
  }

  private void limparTentativas() {
    EntityManager em = emf.createEntityManager();
    try {
      em.getTransaction().begin();
      em.createNativeQuery("DELETE FROM tentativa_cobranca").executeUpdate();
      em.getTransaction().commit();
    } finally {
      em.close();
    }
  }
}
