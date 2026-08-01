package com.globo.assinatura.assinatura;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de persistencia do agregado {@link Assinatura}.
 *
 * <p>Estende {@link JpaRepository} para fornecer as operacoes basicas de CRUD.
 */
public interface AssinaturaRepository extends JpaRepository<Assinatura, Long> {

  /**
   * Verifica se existe assinatura para o usuario em algum dos status informados.
   *
   * @param usuarioId identificador interno do usuario
   * @param status colecao de status considerados abertos
   * @return {@code true} se existir ao menos uma assinatura em um dos status; {@code false} caso
   *     contrario
   */
  boolean existsByUsuarioIdAndStatusIn(Long usuarioId, Collection<StatusAssinatura> status);

  /**
   * Busca uma assinatura pelo uuid publico.
   *
   * @param uuid uuid publico da assinatura
   * @return a assinatura encontrada, ou vazio se nao existir
   */
  Optional<Assinatura> findByUuid(String uuid);

  /**
   * Busca uma assinatura pelo uuid publico adquirindo um lock pessimista de escrita.
   *
   * <p>Executa {@code SELECT ... FOR UPDATE} em SQL nativo, prendendo a linha da assinatura ate o
   * commit da transacao e garantindo exclusao mutua no processamento concorrente de eventos de
   * pagamento para a mesma assinatura. O lock e em nivel de linha, afetando apenas a assinatura
   * alvo. O {@code FOR UPDATE} vive no proprio SQL (em nivel de query nativa o Hibernate nao
   * reescreve a instrucao a partir de {@code @Lock}, por isso o lock fica explicito no SQL).
   *
   * @param uuid uuid publico da assinatura
   * @return a assinatura encontrada sob lock, ou vazio se nao existir
   */
  @Query(value = "SELECT * FROM assinatura WHERE uuid = :uuid FOR UPDATE", nativeQuery = true)
  Optional<Assinatura> buscarPorUuidParaAtualizacao(@Param("uuid") String uuid);

  /**
   * Seleciona assinaturas ativas com proxima renovacao vencida, bloqueando as linhas ate o fim da
   * transacao e pulando as ja bloqueadas por outra instância.
   *
   * <p>Executa {@code SELECT ... FOR UPDATE SKIP LOCKED} em SQL nativo. O {@code SKIP LOCKED}
   * permite que duas instâncias do scheduler processem lotes disjuntos na mesma janela, e o lock de
   * linha impede que a mesma assinatura seja renovada duas vezes. A condicao {@code <=} recupera
   * renovações atrasadas apos indisponibilidade da aplicacao.
   *
   * @param hoje data corrente usada como limite de vencimento
   * @param limite maximo de assinaturas selecionadas por ciclo
   * @return assinaturas vencidas bloqueadas para renovacao
   */
  @Query(
      value =
          "SELECT * FROM assinatura "
              + "WHERE status = 'ATIVA' AND proxima_renovacao_em <= :hoje "
              + "ORDER BY proxima_renovacao_em "
              + "LIMIT :limite "
              + "FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<Assinatura> buscarVencidasParaRenovacao(
      @Param("hoje") LocalDate hoje, @Param("limite") int limite);
}
