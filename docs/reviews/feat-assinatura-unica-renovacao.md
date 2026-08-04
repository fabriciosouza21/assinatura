# Code Review: `feat/assinatura-unica-renovacao`

**Data:** 2026-08-03
**Branch:** `feat/assinatura-unica-renovacao` (worktree `.worktrees/feat-assinatura-unica-renovacao`)
**Entregável:** BE-23. Assinatura única durante renovação: `EM_RENOVACAO` entra no conjunto de status que bloqueiam nova adesão.
**Diff:** 5 arquivos modificados + 1 migration nova (V12), 63 inserções, 4 remoções.
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

Fecha a fresta da invariante "um usuário, uma assinatura aberta". Antes deste PR, um usuário com
assinatura `EM_RENOVACAO` (estado transitório aberto pelo `RenovacaoScheduler` entre o disparo da
renovação e o retorno do gateway) podia abrir uma nova assinatura `AGUARDANDO_PAGAMENTO`. O PR adiciona
`EM_RENOVACAO` ao `STATUS_ABERTOS` em `SolicitarAssinatura` (check explícito) e recria o índice único
parcial `uq_assinatura_aberta_usuario` via V12 para incluir o status no predicado (race fallback via
`DataIntegrityViolationException`). Javadoc de `AssinaturaAbertaException` atualizado. 3 testes novos
(2 unitários, 1 de integração). PRD: `docs/prd/2026-08-03-assinatura-unica-renovacao.md`.

## Veredito

**Clean com sugestões, condicionado a pre-flight de deploy.** Sem critical, sem high. O código está
sólido: as duas camadas de defesa (check explícito + race fallback) foram corretamente acopladas à
mudança do índice, o two-layer guard absorve version skew Java/migration, e o teste de integração
exercita o índice real. O único item que pede atenção antes de aplicar V12 em ambiente com dados é um
pre-flight query (Medium), porque a suíte de testes não cobre o caminho de upgrade. Os Low são
follow-ups opcionais (robustez de teste e fonte única de verdade).

## Critical

Nenhum.

## High

Nenhum.

## Medium

### [security] V12 pode falhar sobre dados legados de produção

`services/assinatura/src/main/resources/db/migration/V12__inclui_em_renovacao_no_indice_assinatura_aberta.sql:3-5`

Lógica auditada e confirmada contra o `develop`. No tronco de integração, `STATUS_ABERTOS = [
AGUARDANDO_PAGAMENTO, ATIVA]` e o índice V3 só cobre esses dois status. `EM_RENOVACAO` já existe no
domínio (shipped em BE-8) e é setado por `RenovacaoScheduler.processar()` ao transitar `ATIVA ->
EM_RENOVACAO` (`Assinatura.iniciarRenovacao()`). Durante essa janela, que dura até o retorno do
gateway, o código antigo **permite** que o mesmo usuário abra uma segunda assinatura
`AGUARDANDO_PAGAMENTO`, pois nem o check explícito nem o índice V3 cobriam `EM_RENOVACAO`.

Se um par `(EM_RENOVACAO, AGUARDANDO_PAGAMENTO)` para o mesmo `usuario_id` existir quando V12 rodar, o
`CREATE UNIQUE INDEX` falha e bloqueia o deploy. Risco adicional de remediação sob pressão: um operador
pode `DELETE` uma das linhas para destravar a migration, destruindo registro financeiro/auditoria.

Rebaixado de High para Medium porque o sistema é pré-produção (handoff: "não foi pushada nem mergeada")
e o estado dos dados é empiricamente verificável com uma query antes do deploy. A janela é estreita
(renovação é estado transitório) e depende do usuário acionar o endpoint de criação exatamente durante
ela.

- **Ação pré-deploy (mandatória em ambiente com dados):**

  ```sql
  SELECT usuario_id, count(*) FROM assinatura
  WHERE status IN ('AGUARDANDO_PAGAMENTO','ATIVA','EM_RENOVACAO')
  GROUP BY usuario_id HAVING count(*) > 1;
  ```

  Se retornar linhas, resolver o conflito (tipicamente: cancelar a `AGUARDANDO_PAGAMENTO` órfã criada
  durante a janela de renovação anterior) antes de aplicar V12.

- **Blind spot do conjunto de testes:** o teste de integração novo
  (`AssinaturaRepositoryTest.deveImpedirNovaAssinaturaQuandoUsuarioTemAssinaturaEmRenovacao`) roda contra
  schema zerado, onde o Flyway aplica V1..V12 do vazio. Ele **não** exercita o upgrade V3 -> V12 sobre
  dados que violam o novo predicado. "Testes verdes" não substitui o pre-flight acima.

### [performance] Rebuild não-concorrente do índice bloqueia escritas durante a migration

`services/assinatura/src/main/resources/db/migration/V12__inclui_em_renovacao_no_indice_assinatura_aberta.sql:1-5`

`DROP INDEX` e `CREATE UNIQUE INDEX` (sem `CONCURRENTLY`) adquiem `ACCESS EXCLUSIVE` lock sobre
`assinatura`. Durante o rebuild ficam bloqueados o endpoint de criação (`SolicitarAssinatura.executar`,
insert + `EXISTS`) e o scheduler de renovação (`buscarVencidasParaRenovacao` com
`FOR UPDATE SKIP LOCKED`). O tipo de preocupação é correto; a estimativa inicial do revisor ("dezenas de
segundos a poucos minutos") está superestimada para este schema: índice de coluna única (`usuario_id`)
com predicado parcial, B-tree construído em um passe em memória. Realista para low-millions: poucos
segundos.

A solução óbvia (`CREATE INDEX CONCURRENTLY`) **não é direta neste projeto**. O auditor invalidou a
recomendação: o Flyway envelopa cada migration em transação por padrão (`application.yaml:29-31`, sem
override por script) e `CONCURRENTLY` não roda dentro de transação. Nenhuma migration V1-V11 usa
`CONCURRENTLY`. A solução exigiria decisões de design (per-script `executeInTransaction=false`, split em
duas migrations, ou adoção de um padrão novo), não é um fix de uma linha.

- **Ação:** avaliar custo/benefício considerando que o rebuild é de poucos segundos. Para ambiente de
  produção com low-millions de linhas, o lock breve provavelmente é aceitável. Se a janela precisar ser
  zero, endereçar como spike separado de padrão de migration concorrente, fora do escopo do BE-23.

## Low

### [quality] Pin test acoplado à ordem da lista

`services/assinatura/src/test/java/com/globo/assinatura/adesao/SolicitarAssinaturaTest.java:112-119`

`deveConsultarAssinaturaAbertaComConjuntoExatoDeStatus` usa
`eq(List.of(AGUARDANDO_PAGAMENTO, ATIVA, EM_RENOVACAO))`. `List.equals` é order-sensitive, então um
reorder puramente cosmético em `STATUS_ABERTOS` quebra o teste sem mudança de comportamento. Ordenação
não é semanticamente relevante para o `IN` do SQL.

- **Test (RED first):** reordenar `STATUS_ABERTOS` em produção (ex.: `EM_RENOVACAO` primeiro) e observar
  o teste falhar sem regression de comportamento.
- **Fix:** trocar `eq(List.of(...))` por
  `argThat(s -> s.size() == 3 && s.containsAll(List.of(AGUARDANDO_PAGAMENTO, ATIVA, EM_RENOVACAO)))`.

### [quality] `STATUS_ABERTOS` agora em três lugares sem fonte única de verdade

`SolicitarAssinatura.java:38-42`, `V12__inclui_em_renovacao_no_indice_assinatura_aberta.sql:4`,
`AssinaturaAbertaException.java:6-8`

O conjunto de "status abertos" agora vive na constante Java, no predicado SQL do índice e enumerado em
prosa no Javadoc. DDD smell: a regra "quais status bloqueiam nova adesão" é invariant de domínio, não
detalhe de implementação do command. Toda vez que um status entra ou sai do conjunto, três pontos
precisam ser editados em lockstep, sem link de compilador/CI entre eles.

- **Fix (follow-up, não bloqueante):** erguer o conjunto para `StatusAssinatura.abertos()` (ou value
  object no pacote de domínio) e referenciar a constante a partir de `SolicitarAssinatura` e do Javadoc,
  em vez de enumerar os status. A deriva SQL -> Java no índice parcial é inevitável (predicado não
  referencia JPA), mas co-localizar o lado Java reduz a superfície.
- **Ação mínima viável agora:** adicionar comentário cross-ref em V12:
  `-- Deve espelhar SolicitarAssinatura.STATUS_ABERTOS`.

### [security] `Assinatura.iniciarRenovacao()` sem guard de transição

`services/assinatura/src/main/java/com/globo/assinatura/assinatura/Assinatura.java:248-250`

Pré-existente (não introduzido por este PR). Todo outro método de transição (`ativar`,
`recusarPagamento`, `renovar`, `cancelar`, `suspender`, `solicitarCancelamento`) valida o status atual;
`iniciarRenovacao()` seta `EM_RENOVACAO` incondicionalmente. Ganha relevância agora que `EM_RENOVACAO`
participa do invariant de unicidade: uma chamada errada sobre uma linha `AGUARDANDO_PAGAMENTO`
silenciosamente a jogaria na janela de unicidade. Documentado como follow-up em
`docs/prd/2026-07-31-dominio-ciclo-renovacao.md:177-179`.

- **Fix (follow-up):** adicionar guard de status-fonte válido (`exigirAtiva()` ou similar).

## Good patterns

- **Duas camadas de defesa corretamente acopladas.** O `catch (DataIntegrityViolationException)` em
  `SolicitarAssinatura.executar:92-94` só passa a pegar `EM_RENOVACAO` porque V12 amplia o predicado do
  índice. As duas mudanças (Java + SQL) são inseparáveis e o PR as faz juntas.
- **Teste de integração no nível certo.**
  `AssinaturaRepositoryTest.deveImpedirNovaAssinaturaQuandoUsuarioTemAssinaturaEmRenovacao` chama
  `iniciarRenovacao()` e força `saveAndFlush` para exercitar o índice parcial real em Postgres, não um
  mock. Usa e-mail distinto (`beltrano@example.com`) para evitar colisão com o teste sibling no modelo
  de DB compartilhado documentado no AGENTS.md.
- **Three independent tests, not redundant.** O pin testa o conjunto exato passado ao repositório; o
  `...EmRenovacao` liga a presença de `EM_RENOVACAO` no conjunto ao comportamento de rejeição; o
  existente `...AssinaturaAberta` usa `any()` (status-agnóstico). Cobertura independente, confirmado
  pelo auditor contra falsa alegação de redundância.
- **409 mantém corpo vazio, sem identificadores.** `AssinaturaAbertaException` carrega mensagem fixa
  sem PII; `AdesaoExceptionHandler` retorna `ResponseEntity<Void>`. Sem widening de privilégio: tratar
  `EM_RENOVACAO` como "aberto" só estreita a janela de segunda adesão.
- **Numeração da migration coordenada.** V12 confirmado livre (V1-V11 existem). Filename segue
  `V<n>__<descricao_ptBR>.sql` em lowercase sem acentos.
- **Commit hygiene correta.** 2 commits conventional PT-BR lowercase sem acento no subject. Split
  lógico: behavior change primeiro (`feat: bloqueia nova assinatura quando usuario esta em renovacao`),
  migration segundo (`feat: inclui em renovacao no indice unico de assinatura aberta`).
- **Version skew Java/migration é benigno.** Confirmado pelo two-layer guard. Se V12 migrar antes do
  novo jar, o código antigo não bloqueia `EM_RENOVACAO` no check explícito, mas o novo índice pega via
  race fallback. Se o novo jar rodar antes de V12, o check explícito bloqueia e o índice antigo não
  atrapalha. Nenhum cenário de deploy quebra a invariante.

## Audit notes

- **Falso positivo descartado: "V12 não é reversível, viola AGENTS.md".** AGENTS.md não tem regra de
  reversibilidade. A frase "aditiva e reversível" aparece scoped à V7 em
  `docs/prd/2026-07-31-dominio-ciclo-renovacao.md:104-106`, não como convenção geral. O projeto não tem
  `flyway undo` nem `*.undo.sql`, e nenhuma migration V1-V11 tem undo. V12 é a primeira migration
  destrutiva do repo (observação válida como nota de ops), mas não é violação de regra.
- **Falso positivo descartado: "teste `...EmRenovacao` é redundante".** Os três testes pinam coisas
  diferentes (detalhado em Good patterns). A alegância de redundância do quality reviewer foi refutada.
- **Recomendação de `CONCURRENTLY` reframada.** A solução apresentada pelo performance reviewer
  (`executeInTransaction=false` + `DROP/CREATE ... CONCURRENTLY`) exige configuração de Flyway que o
  projeto não usa e `executeInTransaction` não é property válida do Spring Boot Flyway (é diretiva
  per-script, needs Teams ou split em duas migrations). O concern (lock exclusivo) é real; o remedy não
  é actionable como escrito. Reframado como spike separado.
- **TOCTOU verificado limpo.** A janela entre `existsByUsuarioIdAndStatusIn:85` e `save:91` é coberta
  pelo `catch (DataIntegrityViolationException):92-94` apoiado no índice parcial, agora também para
  `EM_RENOVACAO`. Race entre `RenovacaoScheduler` (transita `ATIVA -> EM_RENOVACAO`) e insert concorrente
  de nova adesão é pego porque ambos os status estão no predicado.
- **SQL injection descartado.** `existsByUsuarioIdAndStatusIn` é derived method do Spring Data JPA,
  compila para query parametrizada; o argumento é a constante `STATUS_ABERTOS` construída de enums. Sem
  input de usuário no SQL string.
- **IDOR descartado.** `AdesaoController.solicitar` obtém identidade do JWT (`@AuthenticationPrincipal`),
  nunca do body ou query string. Adicionar `EM_RENOVACAO` não muda esse path.

## Plano de ação sugerido (antes do merge)

1. Rodar o pre-flight query contra qualquer ambiente com dados antes de aplicar V12 (Medium, mandatório
   em produção/pré-prod populado).
2. Adicionar comentário cross-ref em V12: `-- Deve espelhar SolicitarAssinatura.STATUS_ABERTOS` (Low,
   uma linha, alinha SQL <-> Java agora).
3. Tornar o pin test order-insensitive via `argThat` com `size + containsAll` (Low, robustez).

Acompanhamento:

- Decisão sobre migration concorrente (`CONCURRENTLY` ou padrão equivalente) como spike separado, se a
  janela de lock de poucos segundos não for aceitável em produção (Medium performance).
- Erguer `STATUS_ABERTOS` para `StatusAssinatura.abertos()` e referenciar a constante no Javadoc (Low,
  fonte única de verdade).
- Adicionar guard de status-fonte em `Assinatura.iniciarRenovacao()` (Low, pré-existente, fica mais
  relevante agora que `EM_RENOVACAO` participa do invariant de unicidade).

## Como reproduzir

```bash
cd services/assinatura
make verify            # lint + 223 testes unitarios, 0 falhas
make test-integration  # sobe Postgres na 5433 (recriado do zero); valida o indice real
```
