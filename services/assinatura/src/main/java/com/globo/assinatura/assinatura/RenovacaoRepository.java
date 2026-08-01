package com.globo.assinatura.assinatura;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de persistencia do agregado {@link Renovacao}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface RenovacaoRepository extends JpaRepository<Renovacao, Long> {

  /**
   * Verifica se ja existe renovacao para a assinatura no ciclo informado.
   *
   * <p>Base da idempotencia por ciclo: impede que o scheduler crie uma segunda renovacao para o
   * mesmo vencimento em execucoes subsequentes.
   *
   * @param assinaturaId identificador interno da assinatura
   * @param cicloReferencia fim do ciclo renovado
   * @return {@code true} se ja existir renovacao para o ciclo; {@code false} caso contrario
   */
  boolean existsByAssinaturaIdAndCicloReferencia(Long assinaturaId, LocalDate cicloReferencia);

  /**
   * Conta quantas renovações ja existem para a assinatura.
   *
   * <p>Suporta o calculo do ordinal do proximo ciclo: {@code count + 1}.
   *
   * @param assinaturaId identificador interno da assinatura
   * @return numero de renovações ja registradas para a assinatura
   */
  long countByAssinaturaId(Long assinaturaId);

  /**
   * Busca uma renovacao pelo uuid publico adquirindo um lock pessimista de escrita.
   *
   * <p>Executa {@code SELECT ... FOR UPDATE} em SQL nativo, prendendo a linha da renovacao ate o
   * commit da transacao e garantindo exclusao mutua no processamento concorrente de eventos de
   * pagamento para a mesma renovacao. O lock e em nivel de linha, afetando apenas a renovacao alvo.
   * O {@code FOR UPDATE} vive no proprio SQL (em nivel de query nativa o Hibernate nao reescreve a
   * instrucao a partir de {@code @Lock}, por isso o lock fica explicito no SQL).
   *
   * @param uuid uuid publico da renovacao
   * @return a renovacao encontrada sob lock, ou vazio se nao existir
   */
  @Query(value = "SELECT * FROM renovacao WHERE uuid = :uuid FOR UPDATE", nativeQuery = true)
  Optional<Renovacao> buscarPorUuidParaAtualizacao(@Param("uuid") String uuid);
}
