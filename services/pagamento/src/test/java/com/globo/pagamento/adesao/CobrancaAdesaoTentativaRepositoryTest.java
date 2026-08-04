package com.globo.pagamento.adesao;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
 * Teste de integracao do {@link CobrancaAdesaoTentativaRepository} contra o Postgres real.
 *
 * <p>Valida o predicado de elegibilidade do scheduler: a query {@code buscarProntasParaCobrar()}
 * deve devolver apenas as tentativas {@code PENDENTE} que ainda nao possuem {@code paymentId} e
 * cujo agendamento venceu, excluindo as ja cobradas pelo gateway, as ja decididas e as em backoff
 * marcadas para o futuro. Usa o Postgres do docker-compose (porta 5433, banco {@code pagamento}).
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
class CobrancaAdesaoTentativaRepositoryTest {

  @Autowired private CobrancaAdesaoTentativaRepository tentativaRepository;
  @Autowired private EntityManagerFactory emf;

  private EntityManager emT1;
  private EntityManager emT2;

  @Test
  @DisplayName("Deve retornar apenas a tentativa pendente sem payment id")
  void deveRetornarApenasTentativaPendenteSemPaymentId() {
    String uuid = UUID.randomUUID().toString();
    CobrancaAdesaoTentativa pronta = new CobrancaAdesaoTentativa(uuid, new BigDecimal("99.90"));
    CobrancaAdesaoTentativa cobrada =
        new CobrancaAdesaoTentativa(UUID.randomUUID().toString(), new BigDecimal("99.90"));
    cobrada.registrarCobranca("pay-1");
    tentativaRepository.save(pronta);
    tentativaRepository.save(cobrada);
    tentativaRepository.flush();

    List<CobrancaAdesaoTentativa> prontas = tentativaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("A tentativa pendente sem payment id e elegivel")
        .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
        .contains(uuid);
    assertThat(prontas)
        .as("A tentativa cobrada nao e elegivel")
        .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
        .doesNotContain(cobrada.getAssinaturaUuid());
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa em backoff antes da data agendada")
  void naoDeveRetornarTentativaEmBackoffAntesDaDataAgendada() {
    String uuid = UUID.randomUUID().toString();
    CobrancaAdesaoTentativa agendadaNoFuturo =
        new CobrancaAdesaoTentativa(uuid, new BigDecimal("99.90"));
    agendadaNoFuturo.agendarPara(Instant.now().plus(3, ChronoUnit.HOURS));
    tentativaRepository.save(agendadaNoFuturo);
    tentativaRepository.flush();

    List<CobrancaAdesaoTentativa> prontas = tentativaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Backoff mantem a tentativa fora do lote ate a data marcada")
        .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
        .doesNotContain(uuid);
  }

  @Test
  @DisplayName("Deve retornar a tentativa em backoff quando a data agendada venceu")
  void deveRetornarTentativaEmBackoffQuandoDataVenceu() {
    String uuid = UUID.randomUUID().toString();
    CobrancaAdesaoTentativa vencida = new CobrancaAdesaoTentativa(uuid, new BigDecimal("99.90"));
    vencida.agendarPara(Instant.now().minus(1, ChronoUnit.MINUTES));
    tentativaRepository.saveAndFlush(vencida);

    List<CobrancaAdesaoTentativa> prontas = tentativaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Tentativa em backoff e elegivel assim que a data vence")
        .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
        .contains(uuid);
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa ja esgotada")
  void naoDeveRetornarTentativaJaEsgotada() {
    String uuid = UUID.randomUUID().toString();
    CobrancaAdesaoTentativa esgotada = new CobrancaAdesaoTentativa(uuid, new BigDecimal("99.90"));
    esgotada.esgotar();
    tentativaRepository.save(esgotada);
    tentativaRepository.flush();

    List<CobrancaAdesaoTentativa> prontas = tentativaRepository.buscarProntasParaCobrar();

    assertThat(prontas)
        .as("Tentativa esgotada nao volta para o lote")
        .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
        .doesNotContain(uuid);
  }

  @Test
  @DisplayName("Nao deve retornar a tentativa travada por transacao concorrente")
  @Transactional(propagation = Propagation.NEVER)
  void naoDeveRetornarTentativaTravadaPorTransacaoConcorrente() {
    String uuid = UUID.randomUUID().toString();
    emT1 = emf.createEntityManager();
    emT2 = emf.createEntityManager();
    try {
      persistirTentativaCommitada(uuid);

      CobrancaAdesaoTentativaRepository repoT1 =
          new JpaRepositoryFactory(emT1).getRepository(CobrancaAdesaoTentativaRepository.class);
      emT1.getTransaction().begin();
      repoT1.buscarProntasParaCobrar();

      CobrancaAdesaoTentativaRepository repoT2 =
          new JpaRepositoryFactory(emT2).getRepository(CobrancaAdesaoTentativaRepository.class);
      emT2.getTransaction().begin();
      emT2.createNativeQuery("SET LOCAL lock_timeout = '2s'").executeUpdate();
      List<CobrancaAdesaoTentativa> resultadoT2 = repoT2.buscarProntasParaCobrar();

      assertThat(resultadoT2)
          .as("T2 nao deve ver a tentativa travada por T1")
          .extracting(CobrancaAdesaoTentativa::getAssinaturaUuid)
          .doesNotContain(uuid);
    } finally {
      rollbackAtivo(emT1);
      rollbackAtivo(emT2);
    }
  }

  private void persistirTentativaCommitada(String uuid) {
    EntityManager em = emf.createEntityManager();
    em.getTransaction().begin();
    em.persist(new CobrancaAdesaoTentativa(uuid, new BigDecimal("99.90")));
    em.getTransaction().commit();
    em.close();
  }

  private void rollbackAtivo(EntityManager em) {
    if (em.getTransaction().isActive()) {
      em.getTransaction().rollback();
    }
    em.close();
  }
}
