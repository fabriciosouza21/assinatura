# Code Review: `feat/teto-falhas-tecnicas-gateway`

**Data:** 2026-08-03
**Branch:** `feat/teto-falhas-tecnicas-gateway` (worktree `.worktrees/feat-teto-falhas-tecnicas-gateway`)
**Entregável:** BE-20. Teto de falhas técnicas do gateway no `CobrancaRenovacaoScheduler`.
**Diff:** 9 arquivos modificados + 1 migration nova (V10), 374 inserções, 35 remoções.
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

Após N falhas técnicas consecutivas do gateway (`CobrancaGatewayIndisponivelException`), o
`CobrancaRenovacaoScheduler` esgota a renovação publicando `RenovacaoTentativasEsgotadas` na outbox,
suspendendo a assinatura pelo fluxo existente. O contador vive em `TentativaCobranca.falhasTecnicas`;
`registrarCobranca` zera no gateway recovery; teto default 3 via `app.renovacao.teto-falhas-tecnicas`.

## Veredito

**Clean com sugestões.** Sem critical, sem high. A implementação está sólida: DDD correto (contador no
agregado, teto como config), `@Transactional` + outbox atômico, consumer compatível, migration segura,
Javadoc e logs no padrão. Os findings são medium/low, em sua maioria doc-drift e defesa-em-profundidade.
Nada bloqueia o commit. Recomenda-se resolver antes do merge os baratos de uma linha e acompanhar os dois
medium (BE-21 e regra-do-três).

## Critical

Nenhum.

## High

Nenhum.

## Medium

### [performance] Pressão no pool de conexões durante outage total do gateway

`services/pagamento/src/main/java/com/globo/pagamento/renovacao/agendador/CobrancaRenovacaoScheduler.java:117,139`

O `cobrar()` é `@Transactional` sobre o loop inteiro, segurando o `FOR UPDATE SKIP LOCKED` e uma conexão
DB através da chamada síncrona ao gateway. O timeout do gateway é **10s** (`GatewayWebClientConfig.java:41`,
não 5s como estimado inicialmente). Num outage total com N tentativas prontas (lote até 200,
`TentativaCobrancaRepository.java:46`), o tick 1 pode segurar até 200 conexões por ~10s cada antes de
esgotar no teto 3. Recém-introduzido: o teto cria a janela de burst limitada, mas intensa.

- **Test (RED):** injetar um `GatewayPagamentoClient` que lança
  `CobrancaGatewayIndisponivelException` após delay configurável; semear 50 PENDENTE; rodar `cobrar()`
  3x; medir pico de uso do pool no tick 1.
- **Fix:** **não resolver aqui.** O roadmap aloca isso no BE-21 (retry/backoff no cliente do gateway).
  Mitigação interim opcional (fora do escopo): setar `proximaTentativaEm` em `registrarFalhaTecnica`
  para espalhar re-tentativas entre ticks. Levar o achado para quem escopa o BE-21.

### [quality] Segunda cópia do builder do envelope `{tipo, ...}`

`services/pagamento/src/main/java/com/globo/pagamento/renovacao/agendador/CobrancaRenovacaoScheduler.java:177-198`
vs `services/pagamento/src/main/java/com/globo/pagamento/webhook/ProcessarWebhookRenovacao.java:204-218`

Os dois métodos constroem o mesmo envelope (`createObjectNode` + `put("tipo",...)` +
`setAll(valueToTree)` + `writeValueAsString` + `OutboxEvent.criar(eventId, "Renovacao", assinaturaId,
eventType, payload)`), mas com assinaturas já divergindo (a do webhook é genérica com 5 params; a do
scheduler é bespoke, hardcoded para ESGOTADO). O contrato puml
`docs/contratos/contrato-eventos-renovacao.puml` documenta esse envelope como contrato travado, então
duas cópias não-alinhadas estruturalmente é risco real de divergência numa futura mudança de shape.

- **Fix:** **não extrair agora.** Regra do três do AGENTS.md: extração é no terceiro, não no segundo.
  Ação correta: (a) deixar com um TODO para a terceira ocorrência, ou (b) o scheduler chamar o método
  genérico do webhook elevado a componente compartilhado. Não espelhar a assinatura genérica agora
  (criaria uma segunda bespoke divergente). Marcar para acompanhar.

### [quality] Contrato puml com nota desatualizada

`docs/contratos/contrato-eventos-renovacao.puml:85-97`

A nota ainda diz apenas "Emitido na 3ª recusa". O Javadoc do record `RenovacaoTentativasEsgotadas` e o
de `esgotar()` foram atualizados para "...pela recusa da ultima tentativa ... ou pelo teto de falhas
tecnicas", mas o puml (que o próprio record cita como contrato travado) ficou para trás. Código e doc
agora discordam sobre o gatilho do evento.

- **Fix:** emendar a nota para "(webhook, 3ª recusa) ou (scheduler, teto de falhas tecnicas
  consecutivas): esgota as tentativas...". Barato e alinha o contrato congelado com o implementado.

### [quality] Acoplamento temporal: 3 passos orquestrados pelo scheduler

`services/pagamento/src/main/java/com/globo/pagamento/renovacao/agendador/CobrancaRenovacaoScheduler.java:142-144`

`registrarFalhaTecnica()` → `esgotouFalhasTecnicas(teto)` → `esgotar()` ficaram como três chamadas
sequenciais cuja ordem é responsabilidade do caller. Todo outro caminho de decisão
(`aprovar`/`recusar`/`esgotar`/`cancelar`) é auto-guardado dentro do agregado. Aqui a regra "teto
atingido esgota" vive no scheduler. Rebaixado de HIGH para MEDIUM porque `teto` é política operacional
(config de deploy), não invariante de domínio, e o AGENTS.md (CQRS Lite) não exige colapsar a
verificação no agregado. O split "o que aconteceu" (`registrarFalhaTecnica`, guardado) vs "o que fazer"
(`esgotouFalhasTecnicas`, query pura) é defensável.

- **Fix (opcional):** colapsar num método do agregado que retorna o desfecho, ex.
  `boolean registrarFalhaTecnicaEVerificarEsgotamento(int teto)` que incrementa, avalia `>= teto` e
  internamente faz `esgotar()`, deixando persistência + outbox no scheduler. Não é mandado pelo
  projeto; fica a critério.

## Low

### [quality] `setCause(e)` omitido no ramo terminal de esgotamento

`services/pagamento/src/main/java/com/globo/pagamento/renovacao/agendador/CobrancaRenovacaoScheduler.java:147-154`

O WARN `cobranca_renovacao_falhas_tecnicas_esgotadas` é o ponto de falha definitiva dessa tentativa,
mas não anexa `.setCause(e)`. O ramo abaixo do teto (`:157-164`) corretamente omite (é retry, não falha
definitiva). AGENTS.md: "Use `setCause(exception)` somente no ponto que determina a falha definitiva."

- **Fix:** adicionar `.setCause(e)` apenas ao WARN esgotado.

### [quality] `registrarCobranca` é o único mutador sem `exigirPendente()`

`services/pagamento/src/main/java/com/globo/pagamento/renovacao/TentativaCobranca.java:135-138`

Todo sibling (`aprovar`/`recusar`/`cancelar`/`esgotar`/`registrarFalhaTecnica`) chama
`exigirPendente()`. `registrarCobranca` seta `paymentId` e zera `falhasTecnicas` sem guarda. Hoje
inalcançável (caller único é o scheduler, pós `buscarProntasParaCobrar` que só devolve PENDENTE com
paymentId nulo). Risco é regressão futura: um novo caller que carregue por id poderia re-estampar
paymentId e zerar o contador numa tentativa decidida, ressuscitando um ciclo fechado.

- **Test (RED first):** `deveRecusarRegistrarCobrancaEmTentativaJaDecidida`:
  `tentativa.esgotar(); assertThatThrownBy(() -> tentativa.registrarCobranca("pay-1"))
  .isInstanceOf(IllegalStateException.class);`
- **Fix:** adicionar `exigirPendente();` como primeira linha.

### [quality] Teste órfão com nome/comentário que citam exceção inexistente

`services/pagamento/src/test/java/com/globo/pagamento/renovacao/agendador/CobrancaRenovacaoSchedulerTest.java:202-227`

`@DisplayName` e o comentário referenciam `NoSuchElementException`/`orElseThrow`, mas o scheduler usa
`if (pagamento.isEmpty()) { ... continue; }` (`CobrancaRenovacaoScheduler.java:127-134`); nenhuma
exceção é lançada. Pré-existente, o teste passa (verifica o caminho `isEmpty()` + `continue` real).
Como o arquivo está sendo tocado, vale corrigir.

- **Fix:** renomear para `naoDeveEnvenenarLoteQuandoTentativaOrfaEPulada` + `@DisplayName("Nao deve
  envenenar o lote quando uma tentativa orfa e pulada")`; reescrever o comentário. As asserções ficam
  verdes.

### [audit] Coordenação do número da migration V10 entre worktrees

`services/pagamento/src/main/resources/db/migration/V10__adiciona_falhas_tecnicas_tentativa_cobranca.sql`

AGENTS.md pede coordenar numeração entre worktrees do mesmo serviço. Hoje V10 é o próximo número livre
(V9 é o maior comitado): **não há colisão agora**. Mas esta migration está uncommitted enquanto
múltiplas worktrees de `services/pagamento` existem. Se um branch irmão landar V10 primeiro, a baseline
do Flyway quebra no merge.

- **Fix:** ao mergear, reconfirmar o maior número de migration no `develop` e renumerar se preciso.
  Risco de processo, não defeito de código.

### [quality] Casos de contorno faltando em `esgotouFalhasTecnicas`

`services/pagamento/src/test/java/com/globo/pagamento/renovacao/TentativaCobrancaTest.java:78-92`

Igualdade-no-teto (`esgotouFalhasTecnicas(3)` true após 3 falhas) e abaixo-do-teto (`(4)` false) já
estão cobertos. Faltam: (a) `teto=1` (esgota na primeira falha, o mínimo de config agressiva), e (b)
**overshoot** (`falhasTecnicas=4, teto=3` → true). O overshoot importa porque `registrarFalhaTecnica`
incrementa *antes* do check: um bug futuro de dupla-contagem esgotaria um tick cedo e o `>=`
mascararia o over-count.

- **Fix:** dois testes one-liners pinando a semântica `>=`.

### [audit] Falta de abstração de `Clock` no pagamento (gap de testabilidade)

`CobrancaRenovacaoScheduler.java:182` (`Instant.now()`)

O scheduler usa `Instant.now()` direto, igual ao restante do serviço
(`ProcessarWebhookRenovacao.java:149,175,191`). O serviço de assinatura **já** injeta
`java.time.Clock` (`services/assinatura/src/main/java/com/globo/assinatura/renovacao/ProcessarRenovacaoResultado.java:53,87`),
mas o pagamento não tem bean de `Clock` em lugar algum. Isso torna o timestamp do evento scheduler-origin
não determinístico em testes. **Gap pré-existente, service-wide, fora do escopo** do BE-20. Nota para
roadmap: alinhar o pagamento ao padrão que a assinatura já estabeleceu.

## Good patterns

- **DDD correto**: contador persistente no agregado que o possui; teto como config externalizada, não
  enterrado no domínio.
- **`registrarFalhaTecnica` auto-guardado** via `exigirPendente()`, coberto por
  `deveRecusarRegistrarFalhaTecnicaEmTentativaJaDecidida`.
- **Teste de integração é integration-grade de verdade**: Postgres real, path
  `FOR UPDATE SKIP LOCKED`, loop de 3 ticks recarregando a linha a cada tick, `@AfterEach` limpando a
  outbox. Migração das fixtures para UUIDs válidos com constantes compartilhadas melhora o padrão.
- **Logs Fluent no padrão**: `event` snake_case (`cobranca_renovacao_falhas_tecnicas_esgotadas`,
  `cobranca_renovacao_falha_gateway`), `addKeyValue` para dinâmicos, `.log()` curto sem interpolação,
  toda cadeia termina em `.log()`.
- **Javadoc completo**: todo símbolo público novo abre com frase descritiva (sem violação de
  `SummaryJavadoc`), usa `{@code ...}`, documenta `@param`/`@return`/`@throws` com a condição
  disparadora.
- **Construtor fail-fast** em `tetoFalhasTecnicas < 1` com mensagem clara e teste dedicado;
  misconfiguração explode no boot do contexto.
- **Duas validações `teto < 1` defensáveis** (defesa-em-profundidade no boundary certo): construtor
  protege o path deployado; `esgotouFalhasTecnicas` protege a API pública do agregado contra callers
  diretos.
- **Naming PT-BR consistente** entre código, config, env e Javadoc.
- **Migration minimal e segura**: `INTEGER NOT NULL DEFAULT 0` correto para linhas existentes (sem
  backfill) e novas tentativas.

## Audit notes

- **Falso positivo descartado**: Security [LOW] "log forging via `renovacaoId` String". `renovacaoId`
  vem do construtor com guarda non-blank, populado por `PagamentoRenovacao`; não é input controlável
  pelo usuário neste path. Requereria breach prévia de integridade. Não é finding de segurança. (A
  inconsistência String-vs-UUID que força os `UUID.fromString` repetidos é smell de manutenção, não
  segurança.)
- **Correção de número**: o timeout do gateway é **10s** (`GatewayWebClientConfig.java:41`), não 5s. O
  achado de performance se fortalece.
- **Idempotência verificada limpa**: o caminho scheduler é interno (sem novo entrypoint externo); não
  dá para forjar um `ESGOTADO` sem acesso ao DB/topic; `UUID.randomUUID()` é CSPRNG-adequado para
  dedup; sem race de double-charge/double-esgotar (o predicado exclui esgotadas);
  `tentativa.save` + `outbox.save` atômicos na mesma tx.
- **Consumer compatível**: `RenovacaoResultadoConsumer.validar(RenovacaoTentativasEsgotadas)` exige
  eventId/renovacaoId/assinaturaId não-nulos; o evento scheduler-origin satisfaz. O discriminador
  `tipo:"ESGOTADO"` bate com `extrairTipo`, pinado no unit test.

## Plano de ação sugerido (antes do merge)

1. Emendar a nota do puml `contrato-eventos-renovacao.puml` (medium doc-drift).
2. Adicionar `.setCause(e)` ao WARN esgotado (low, um arquivo).
3. Adicionar `exigirPendente()` em `registrarCobranca` com RED test (low, defesa-em-profundidade).
4. Renomear/recomentar o teste órfão (low, cosmético).
5. Adicionar 2 testes de contorno em `esgotouFalhasTecnicas` (low, pinar `>=`).
6. Reconfirmar o número V10 no `develop` antes do merge (processo).

Acompanhamento:

- BE-21 endereça a pressão de pool (medium performance).
- Terceira ocorrência do envelope `{tipo,...}` dispara extração (medium quality).
- `Clock` no pagamento é item de roadmap service-wide (audit).
