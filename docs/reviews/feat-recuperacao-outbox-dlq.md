# Code Review: `feat/recuperacao-outbox-dlq`

**Data:** 2026-08-03
**Branch:** `feat/recuperacao-outbox-dlq` (worktree `.worktrees/feat-recuperacao-outbox-dlq`)
**Entregável:** BE-24. Recuperação automática da outbox: eventos `FALHA` voltam ao ciclo de publicação após o timeout da DLQ, via novo status `RETENTATIVA_DLQ`, com scheduler de quarentena, limite de ciclos e `FOR UPDATE SKIP LOCKED`. Espelhado nos dois serviços.
**Diff:** 19 arquivos, +667, -14 (11 commits, base `develop` = `380bad4`).
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

Adiciona um segundo scheduler de outbox (`OutboxRecuperacaoScheduler`) que drena eventos em `FALHA`
após um timeout configurável, promove-os a `RETENTATIVA_DLQ` (zera `tentativas`, incrementa
`ciclosRecuperacao`, agenda `proximaTentativaEm = agora`) e os devolve ao `OutboxPublisher` via
widening de `buscarPublicaveis` para `status IN ('PENDENTE','RETENTATIVA_DLQ')`. O gate de esgotamento
fica no repositório (`ciclos_recuperacao < :maxCiclos` na native query com `FOR UPDATE SKIP LOCKED`).
Novo enum `RETENTATIVA_DLQ`, campo `ciclosRecuperacao` (short), migrations V13 (assinatura) / V11
(pagamento) recriando `idx_outbox_polling` como parcial ampliado e adicionando
`idx_outbox_recuperacao_dlq`. Config `app.outbox.dlq.{intervalo-ms,timeout-segundos,max-ciclos}`.
Implementação TDD; testes unitários e de integração verdes. PRD:
`docs/prd/2026-08-03-recuperacao-automatica-outbox.md`.

## Veredito

**Needs fixes.** Sem critical/blocking de corretude ou segurança. O core funciona, o estado-máquina
fecha (`FALHA → RETENTATIVA_DLQ → PUBLICADO | FALHA`), `ciclos_recuperacao < :maxCiclos` gateia o
esgotamento e os índices pariais seguem o precedente da V5. Há dois High que valem corrigir antes do
merge (isolação por item no scheduler + teste end-to-end da recuperação), um terceiro High de
endurecimento barato (pool size do scheduler) e o restante é hardening/follow-up.

## Critical

Nenhum.

## High

### [quality] `OutboxRecuperacaoScheduler` diverge do house style de schedulers de lote

`services/assinatura/src/main/java/com/globo/assinatura/shared/outbox/OutboxRecuperacaoScheduler.java:54-70`
(mirrored: `services/pagamento/.../OutboxRecuperacaoScheduler.java`)

O scheduler é estruturalmente um irmão de `RenovacaoScheduler.varrerVencimentos` e
`CobrancaRenovacaoScheduler.cobrar`, mas omite três convenções que motivam o padrão para schedulers de
lote sobre `FOR UPDATE SKIP LOCKED`:

1. **Sem try/catch por item.** O `@Transactional` envolve o `for` inteiro. Se
   `outboxRepository.save(evento)` lançar num único evento, o lote inteiro é revertido e todos os
   recuperados daquele tick permanecem em `FALHA` até o próximo ciclo, frustrando a intenção de
   recuperação. Os schedulers de referência isolam a falha por linha
   (`RenovacaoScheduler.java:104-114`): uma linha ruim loga WARN e o lote segue.
2. **Sem logs `*_batch_inicio`/`*_batch_fim` DEBUG** com `tamanhoLote`. Perde-se observabilidade de
   cadência e tamanho de lote que os outros dois fornecem consistentemente.
3. **Sem `initialDelayString`.** `CobrancaRenovacaoScheduler.java:84-93` documenta que o delay inicial
   existe para evitar a corrida entre o run agendado e a chamada direta de teste sobre o `SKIP LOCKED`.
   Aqui o primeiro tick dispara no boot.

O per-item try/catch é a parte load-bearing: a regressão de robustez versus o padrão estabelecido não é
cosmética.

- **Test (RED first):** "Não deve abortar o lote quando um evento falha ao recuperar" — stub de
  `outboxRepository.save` lançando no primeiro de dois eventos; assertar que o segundo ainda é
  promovido a `RETENTATIVA_DLQ`.
- **Fix:** espelhar `RenovacaoScheduler` (try/catch por item emitindo
  `event=outbox_dlq_falha_isolada` em WARN), adicionar `outbox_dlq_batch_inicio`/`_fim` em DEBUG com
  `tamanhoLote`, e `initialDelayString = "${app.outbox.dlq.intervalo-ms}"`.

### [quality] Sem teste end-to-end de `RETENTATIVA_DLQ` → `publicarPendentes` → `PUBLICADO`

`services/assinatura/src/test/java/com/globo/assinatura/shared/outbox/OutboxPublisherIntegracaoTest.java`
(intacto)

A widening de `buscarPublicaveis` para `IN ('PENDENTE','RETENTATIVA_DLQ')` só tem cobertura em nível de
repositório (`OutboxRepositoryTest.deveSelecionarEventosEmRetentativaDlq`). Nenhum teste exercita um
evento recuperado fluindo pelo `OutboxPublisher.publicar` até `marcarPublicado` → `PUBLICADO`. O
critério de aceitação do PRD ("voltar ao ciclo normal de publicação") está coberto só na camada de
dados. O publisher é inalterado no tratamento de linhas selecionadas, mas o ponto de junção (status +
`proximaTentativaEm` fazendo o evento efetivamente circular pelo publisher) é justamente o que não
tem asserção.

- **Test (RED first):** integração com `@EmbeddedKafka` e Postgres 5433, semear um evento via
  `marcarFalha` + `recuperarParaRetentativa` (com `falhouEm` anterior ao timeout), invocar
  `recuperarFalhas()` e `publicarPendentes()`, assertar `PUBLICADO` e o payload no tópico capturado.

### [performance/audit] Pool do scheduler = 1 compartilhado entre publisher (bloqueante) e recovery

`services/assinatura/src/main/java/com/globo/assinatura/shared/kafka/MessagingConfig.java:27`
(assinatura), `services/pagamento/.../PagamentoApplication.java:14` (pagamento)

Verificado: não há `spring.task.scheduling.pool.size` em nenhum dos dois serviços. O default do
`ThreadPoolTaskScheduler` do Spring Boot é 1. O `OutboxPublisher.publicar` faz
`kafkaTemplate.send(...).get()` bloqueante dentro de `@Transactional`
(`OutboxPublisher.java:83`). Cada serviço roda agora 3 jobs `@Scheduled` numa thread só (assinatura:
`RenovacaoScheduler`, `OutboxPublisher`, `OutboxRecuperacaoScheduler`; pagamento:
`CobrancaRenovacaoScheduler`, `OutboxPublisher`, `OutboxRecuperacaoScheduler`). Um stall no ack do
Kafka atrasa o recovery e o RenovacaoScheduler, e vice-versa.

Rebaixado de Critical para High pelo auditor e confirmado: a causa-raiz é pré-existente (este PR
adiciona um consumidor a um pool já congestionado, não introduz o padrão) e o pior caso realístico é
tick atrasado, não perda de dados (o recovery é idempotente e se corrige no próximo ciclo). Vale
endurecer aqui porque o PR agrava o problema.

- **Fix:** `spring.task.scheduling.pool.size: 3` (ou mais) no `application.yaml` dos dois serviços.
  Alternativa: expor um `ThreadPoolTaskScheduler` dedicado para os schedulers de outbox.

## Medium

### [quality] Javadoc da classe `OutboxEvent` desatualizado

`services/assinatura/src/main/java/com/globo/assinatura/shared/outbox/OutboxEvent.java:13-19`
(mirrored em pagamento)

O class Javadoc ainda descreve só `PENDENTE → PUBLICADO | FALHA`. Não menciona `RETENTATIVA_DLQ` nem o
ciclo de recuperação, embora o agregado agora carregue esse estado e essa transição. AGENTS.md exige
que Javadoc descreva o comportamento atual.

- **Fix:** estender o doc da classe com o ciclo completo
  (`FALHA → RETENTATIVA_DLQ → PUBLICADO | FALHA`). Aproveitar para completar o Javadoc do enum
  `RETENTATIVA_DLQ` (omitiu o caminho de falha-de-novo de volta a `FALHA`) e de
  `recuperarParaRetentativa` (omitiu o contrato do cap de ciclos e a elegibilidade imediata).

### [performance/quality] Recuperação sem jitter gera thundering herd no publisher

`services/assinatura/src/main/java/com/globo/assinatura/shared/outbox/OutboxEvent.java:140`,
`OutboxRecuperacaoScheduler.java:61` (mirrored em pagamento)

`recuperarParaRetentativa` seta `proximaTentativaEm = agora` (imediato, sem jitter). Um lote de até
`tamanho-lote` (default 100) eventos recuperados vira elegível no mesmo tick do publisher, que faz
sends bloqueantes seriais numa transação só. O jitter do `RetryPolicy` só entra após uma falha de
publish, nunca na recuperação.

Rebaixado de High para Medium pelo auditor e confirmado: o timeout de DLQ (3600s) já espalha
`falhou_em` numa janela ampla, então o pior caso simétrico exige uma falha massiva correlacionada (ex.:
outage prévia do Kafka). Mesmo assim, o impacto é latência elevada, não processamento duplicado
(consumer deduplica por `eventId`).

- **Fix:** passar `retryPolicy` (ou um jitter) ao scheduler e setar
  `proximaTentativaEm = agora.plus(jitter)`, reaproveitando `calcularProximoAtraso`.

### [audit] `reasonCode = exception.getMessage()` no publisher da assinatura, amplificado pela recuperação

`services/assinatura/src/main/java/com/globo/assinatura/shared/outbox/OutboxPublisher.java:109,120,124-126`

AGENTS.md:166 proíbe `exception.getMessage()` como campo de log. O publisher da assinatura loga
`reasonCode = mensagem(erro)` (mensagem crua da exceção) e a persiste em `ultimoErro` via
`registrarFalha`/`marcarFalha`. O gêmeo do pagamento usa `reasonCode = "envio_falhou"` estável.
Pré-existente, mas este PR amplia o blast radius: eventos recuperados re-entram nesse publisher, então
uma `FALHA` com mensagem de exceção é re-logada a cada ciclo de recuperação.

- **Fix:** alinhar a assinatura ao padrão do pagamento (`reasonCode` estável, sem payload da exceção).
  Fora do escopo estrito do BE-24, mas vale pegar junto dado o efeito composto.

## Low

### [performance] Índice `idx_outbox_recuperacao_dlq` omite `ciclos_recuperacao`

`services/assinatura/src/main/resources/db/migration/V13__adiciona_recuperacao_dlq_outbox.sql:8-10`,
`services/pagamento/.../V11__adiciona_recuperacao_dlq_outbox.sql:8-10`

O partial index é `(falhou_em) WHERE status='FALHA'`; a query filtra também
`ciclos_recuperacao < :maxCiclos`. Linhas `FALHA` com ciclos esgotados são terminais (nenhum caminho
de código as tira de `FALHA`) e acumulam sem bound, forçando o executor a rejeitar post-filter. Hoje o
conjunto é pequeno; degrada só se houver acúmulo sem limpeza operacional.

- **Fix (opcional, forward-looking):** índice composto `(falhou_em, ciclos_recuperacao)`.

### [quality] `save()` redundante em entidade managed

`OutboxRecuperacaoScheduler.java:60,65` (ambos os serviços)

Entidades carregadas na transação são flushed no commit; o `save(evento)` explícito apenas descarta o
retorno. Sem JDBC batching configurado, são N `UPDATE`s no lote.

- **Fix (opcional):** remover as chamadas explícitas; o flush no commit basta.

### [audit] Sem retenção/arquivo para `FALHA` terminal

`V13__adiciona_recuperacao_dlq_outbox.sql` / `V11__...`, `OutboxRepository.buscarRecuperaveis`

Eventos que esgotam `maxCiclos` ficam em `FALHA` definitivamente, sem path de `DELETE`/archive. O
partial index `WHERE status='FALHA'` cresce sem bound. Não é bug de corretude; é preocupação
operacional. Este PR é o lugar natural para ao menos documentar a expectativa de retenção.

## Good patterns

- **Índices pariais seguem o precedente da V5** (`WHERE status='PENDENTE'`) e da V5 do pagamento
  (tentativa cobrança pendente). A widening do predicado de `idx_outbox_polling` acompanha a query de
  `buscarPublicaveis`. Alinhado.
- **Gate de esgotamento no repositório.** `buscarRecuperaveis` com `ciclos_recuperacao < :maxCiclos`
  deixa a regra na fonte única; o scheduler delega em vez de re-checar em Java.
- **Logging Fluent com `event=outbox_dlq_recuperada`**, sem payload/PII, sem `setCause` (não há ponto
  de falha definitiva aqui). Conforme AGENTS.md.
- **Paridade assinatura↔pagamento real e verificada.** Os dois `OutboxRecuperacaoScheduler` são
  byte-identical módulo pacote. As migrations V13/V11 são idênticas.
- **`OutboxRecuperacaoSchedulerTest`** usa `ArgumentCaptor` + `isCloseTo(..., byLessThan(2, SECONDS))`
  para tolerar drift de relógio sem enfraquecer o assert de parâmetros (`eq(3)`, `eq(100)`). Tradeoff
  sensato.

## Audit notes

- **FP rejeitada:** "`contains` vs `containsExactly` mascara defeito de isolamento de teste" — é
  convenção documentada (AGENTS.md:99-104, base 5433 compartilhada sem cleanup, execução via
  `make test-integration` que recria o Postgres). O pattern `.contains` já existia no `develop` em
  `OutboxRepositoryTest:77-84`. Rejeitado.
- **FP rejeitada:** "guarda de pré-condição em `recuperarParaRetentativa` como achado de segurança" —
  não explorável; o único caller vem de `buscarRecuperaveis` filtrado por `status='FALHA'`. O ângulo de
  Javadoc consolida no Medium acima.
- **Contradição do performance:** "sem interação entre os dois schedulers da outbox" vs "contensão de
  pool=1" não podem coexistir. A de pool procede (verificado); a "sem interação" vale só no nível
  lógico da tabela, não de thread.
- **Duplicação do código de outbox entre os dois serviços:** decisão arquitetural aceita (unidades
  implantáveis separadas, schemas e Flyway próprios). Não é defeito. Custa manutenção: qualquer fix no
  scheduler precisa ser aplicado duas vezes. Um comentário `// mantenha em paridade com
  services/<outro>` tornaria o contrato explícito.

## Pontos de atenção sugeridos pelo handoff

- **PRD untracked na worktree** (`docs/prd/2026-08-03-recuperacao-automatica-outbox.md`): commitar
  junto (docs) ou descartar antes do merge.
- **Porta manual, sem cherry-pick** dos commits antigos `2f6eaa5`/`c9b6167`: usam pacote pré-ADR
  `com.globo.assinatura.outbox` e migration `V7` que colide com o develop. Decisão acordada; sem
  objeção.
- **Containers `assinatura-postgres`/`assinatura-redis`** podem estar de pé na porta 5433 da última
  integração. Derrubar antes de rodar integração de outra worktree.

## Situacao apos os fixes

Todos os High e Medium resolvidos, mais os dois Lows baratos. Verificado com `make verify` e
`make test-integration` nos dois serviços (assinatura: 227 unit + 35 integracao; pagamento:
150 unit + 31 integracao).

- **[High] Isolacao por item no scheduler (TDD RED first):** novo teste
  `naoDeveAbortarLoteQuandoEventoFalhaAoRecuperar` falhou antes (excecao abortava o lote) e passou
  apos o fix. `recuperarFalhas` agora isola `RuntimeException` por evento (WARN
  `event=outbox_dlq_falha_isolada`), loga `outbox_dlq_batch_inicio`/`_fim` em DEBUG com
  `tamanhoLote` e usa `initialDelayString` no agendamento. Espelhado nos dois servicos, byte-identical.
- **[High] Teste end-to-end `RETENTATIVA_DLQ` → `publicarPendentes` → `PUBLICADO`:** novo teste
  `deveRecuperarEventoDaDlqPublicarNoTopico` no `OutboxPublisherIntegracaoTest` semeia evento em
  `FALHA` (falhouEm anterior ao timeout), invoca `recuperarFalhas()`, asserta a promocao, invoca
  `publicarPendentes()`, asserta `PUBLICADO` no banco e o registro no topico capturado (key =
  aggregateId, payload do contrato). Props de teste zeram backoff/jitter para o evento recuperado
  ser elegivel de imediato.
- **[High] Pool do scheduler:** `spring.task.scheduling.pool.size: ${SPRING_TASK_SCHEDULING_POOL_SIZE:3}`
  nos dois `application.yaml`, documentado no `.env.example`.
- **[Medium] Javadocs:** classe `OutboxEvent` cobre o ciclo completo
  (`FALHA → RETENTATIVA_DLQ → PUBLICADO | FALHA` ate o cap de ciclos); enum `RETENTATIVA_DLQ`
  documenta o retorno a `FALHA`; `recuperarParaRetentativa` documenta o cap de ciclos no repositorio
  e a elegibilidade sem reaplicar o timeout (param renomeado para `proximaTentativa`).
- **[Medium] Jitter na recuperacao:** o scheduler recebe a `RetryPolicy` e agenda
  `proximaTentativaEm = agora + calcularProximoAtraso(1)`, espalhando a elegibilidade do lote e
  evitando thundering herd no publisher.
- **[Medium] `reasonCode` estavel:** publisher da assinatura usa `reasonCode=envio_falhou` (padrao
  do pagamento) em vez de `exception.getMessage()`; a mensagem crua continua persistida em
  `ultimoErro`.
- **[Low] Indice composto:** `idx_outbox_recuperacao_dlq` agora e `(falhou_em, ciclos_recuperacao)`
  nas V13/V11 (edicao segura: migrations novas nesta branch).
- **[Low] Retencao e jitter documentados:** seccao "Notas operacionais" no PRD com a expectativa de
  retencao das `FALHA` terminais.
- **Paridade explicita:** Javadoc dos dois schedulers marca o contrato de paridade
  (`services/pagamento` / `services/assinatura`).
- **Nao feito (decisao):** remocao do `save()` redundante por item. O teste prescrito pelo proprio
  review (stub de `save` lancando) depende da chamada explicita, e o house style
  (`RenovacaoScheduler`, `CobrancaRenovacaoScheduler`) tambem salva explicitamente. Manteve.
- **Observacao:** a integracao do assinatura flakeou uma vez em
  `RenovacaoResultadoConsumerIntegracaoTest.deveEnviarPayloadInvalidoParaTopicoDeFalha` (timeout de
  await de 10s no topico DLQ do consumer, fluxo pre-existente e nao relacionado); passou na
  reexecucao com a base recriada.
