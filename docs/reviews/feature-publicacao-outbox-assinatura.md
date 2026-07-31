# Code Review: feature/publicacao-outbox-assinatura

**Branch:** `feature/publicacao-outbox-assinatura` (worktree `.worktrees/outbox-assinatura`)
**Base:** `develop` (diff: 18 arquivos, +1013/-7, 10 commits)
**Data:** 2026-07-30
**Metodo:** `/review` autonomous (scout + security/performance/quality em paralelo + red-team audit), seguido de correcoes TDD
**Entrega:** BE-3 do roadmap — publicacao de `AssinaturaSolicitada` via outbox transactional

## Resumo

A entrega implementa o padrao outbox no Assinatura Service: `SolicitarAssinatura` grava o insert da
assinatura e o evento na mesma transacao; `OutboxPublisher` (`@Scheduled`) drena a outbox para o
topico `assinatura-solicitada` com retry/DLQ. A atomicidade da **escrita** estava correta desde o
inicio. O defeito estava na **publicacao**: o `@Transactional` em `publicarPendentes()` fechava a
transacao antes do callback assincrono do Kafka disparar, quebrando at-least-once, a DLQ e o
`FOR UPDATE SKIP LOCKED`.

## Veredito final

**Necessita correcoes** -> **corrigido** (1 Critical + 1 Medium resolvidos; 4 descartados/adiados).
`make verify` verde: 0 violacoes Checkstyle, Spotless limpo, 46 testes unitarios passando.

## Findings

### Corrigidos

#### [Critical] Callback async do Kafka executava fora de @Transactional
**Arquivo:** `services/assinatura/src/main/java/com/globo/assinatura/outbox/OutboxPublisher.java`

`publicarPendentes()` era `@Transactional` e chamava
`kafkaTemplate.send(...).whenComplete((r,e) -> tratarResultado(...))`. Como `KafkaTemplate.send` e
non-blocking e a `CompletableFuture` completa na thread de I/O do produtor apos o metodo retornar,
o proxy transacional committava antes do `whenComplete` disparar. Assim o
`outboxRepository.save(evento)` rodava na thread do callback, sem tx ativa e com a entidade
destacada.

Consequencias:
1. Eventos bem-sucedidos nunca saiam de `PENDENTE` -> reenviados a cada ciclo (5 s) indefinidamente.
2. Os locks de `FOR UPDATE SKIP LOCKED` eram liberados no commit, antes do ack -> o SKIP LOCKED nao
   serializava entre ciclos -> duas instancias re-selecionavam a mesma linha.
3. A DLQ (3 tentativas) era ignorada: o caminho de sucesso nao avancava `tentativas`.

Severidade mantida em Critical porque **nao existe consumer neste branch** (a dedup por `eventId`
vive em outra branch). Nao se pode rebaixar com base numa mitigacao nao entregue.

**Teste RED:** `OutboxPublisherTest.devePersistirResultadoNaMesmaThreadDaTransacao` forca o ack a
completar noutra thread e captura a thread do `save`. Falhava com `expected: "main" but was:
"pool-2-thread-1"` (thread do callback), provando o bug.

**Correcao:** envio bloqueante dentro da transacao
(`kafkaTemplate.send(...).get()`), com `marcarPublicado`/`tratarFalha` in-method antes do commit.
Elimina tambem o `catch (RuntimeException)` morto apontado pelo auditor (todo caminho converge para
um unico `save` na thread do publisher). Custo aceitavel nos volumes atuais (dev local, lote 100).

#### [Medium] Producer Kafka sem acks/enable.idempotence pinados
**Arquivo:** `services/assinatura/src/main/resources/application.yaml`

So declarava serializadores. PINEI `acks=all`, `retries=2147483647` e
`enable.idempotence=true` (com overrides via env), tornando explicita a semantica de entrega que o
outbox assume, em vez de depender do default do Spring Boot. Alinhado com "configuracoes
externalizadas" do AGENTS.md.

#### [Medium] Teste de atomicidade da escrita
**Arquivo:** `SolicitarAssinaturaTest.java`

A propriedade "falha na outbox propaga para rollback do insert da assinatura" nao tinha teste.
Adicionei `devePropagarFalhaDaOutboxParaRollbackDoInsert`, que forca `outboxRepository.save` a
lanca e assevera que a excecao escapa de `executar` (pre-requisito do rollback). A propagacao ja
estava correta; o teste documenta e protege contra regressao.

#### [Low] Cobertura de testes
- **Javadoc obsoleto** em `OutboxRepositoryTest`: dizia "migracao V4", mas o arquivo e
  `V5__cria_tabela_outbox.sql`. Corrigido.
- **Teste de integracao asseverava so a `key` do Kafka**, nunca o `payload`. Adicionada assercao
  do campo `assinaturaId` no `value` consumido.
- **`AssinaturaSolicitadaTest` pinava so o valor do `PREMIUM`**. Parametrizado sobre
  BASICO/PREMIUM/FAMILIA (`@ParameterizedTest`).

### Descartados (falsos positivos ou nao-acionaveis)

#### [Medium] Migrar Plano.from @JsonCreator de Jackson 2 para Jackson 3
**Falso positivo verificado empiricamente.** Revisor/auditor afirmaram que Jackson 3 nao reconhece
`com.fasterxml.jackson.annotation.JsonCreator`, tornando `Plano.from` codigo morto e regredindo o
400 de plano invalido. Rodei o teste existente
`AssinaturaControllerTest.deveRetornarBadRequestAoEnviarPlanoInvalido`: **passa**. O Spring Boot 4
honra a anotacao do Jackson 2 (bridge de compatibilidade no classpath). Migrar seria mudanca
desnecessaria em codigo que funciona. Nao acionado.

#### [Medium] Mover decisao retry/DLQ para a entidade OutboxEvent
Nao e bug, e judgment de design. A invariant `PENDENTE => tentativas < max` e mantida pela logica
do publisher e coberta por `deveMarcarComoFalhaAoEsgotarTentativas` (que confirma passagem apos a
correcao do Critical). Mover `RetryPolicy` para dentro da entidade JPA aumentaria o acoplamento
persistencia/politica sem resolver defeito manifesto. Descartado.

#### [Medium] Adicionar bean TaskScheduler em MessagingConfig
Adiado como divida. Hoje so existe uma tarefa `@Scheduled` e `fixedDelay` previne sobreposicao. O
risco de stall so se materializa ao adicionar uma segunda tarefa; cobrivel naquele momento com um
unico bean. Adicionar por antecipacao viola "no features beyond what was asked".

#### [Low] ORDER BY criado_em vs indice parcial
Nao acionado. O `ORDER BY criado_em` (coluna nao-lider apos o filtro do indice parcial
`(status, proxima_tentativa_em, criado_em) WHERE status='PENDENTE'`) gera no de `Sort`. Em volumes
de dev local e irrelevante; se o backlog crescer (Kafka fora do ar por minutos), vale reconsiderar
`ORDER BY proxima_tentativa_em, criado_em`. Registrado como observacao de performance futura.

## Padroes positivos (mantidos)

- `RetryPolicy` como value object puro, com matematica isolada e testada por range.
- `OutboxEvent` encapsula transicoes em metodos nomeados; `criar(...)` deixa o evento imedativel
  publicavel.
- Disciplina de Javadoc em todos os publicos novos; `OutboxStatus` documenta cada constante.
- Estilo de teste coerente (PT-BR, `.as(...)`, `@Tag("integration")`).
- Externalizacao de config completa (`${ENV:default}`).
- Query nativa livre de injecao (`@Param` bound) — verificado.
- `eventId` via `UUID.randomUUID` (CSPRNG), PK e dedup key unificados; `aggregateId` correto como
  chave de particao. Sem novos endpoints HTTP -> `SecurityConfig` inalterado, correto.

## Auditoria

Os tres revisores convergiram independentemente no bug central (bom sinal). Duas correcoes da
auditoria:
1. O motivo de os testes mascararem divergiu: o teste **unitario** usa `completedFuture` (callback
   sincrono); a **integracao** mascara porque assevera Kafka, nao estado de DB. Sao dois motivos.
2. A premissa do prompt (consumer dedup mitiga) nao se aplica: nao ha consumer neste branch.
   Severidade mantida em Critical.

O auditor adicionou blind spots que o trio errou: rollback de serializacao sem teste (corrigido),
validacao de `JSONB` (risco latente, nao acionado), scheduler single-thread (adiado), `catch`
morto (corrigido junto com o Critical).

## Desdobramento: fix do teste de integracao do publisher

Ao rodar a integracao para validar, o `OutboxPublisherIntegracaoTest.devePublicarEventoPendenteNoTopico`
falhava com timeout. Diagnostico por isolamento (banco efemero limpo):

- O publisher **funciona**: o evento transitava para `PUBLICADO` e a mensagem chegava ao topico
  (confirmado por um consumer de diagnostico que listou 1 registro).
- O defeito estava no **proprio teste**, nao no codigo: um `KafkaConsumer` manual com `poll` dentro
  do `await` perdia a mensagem porque a particao ainda nao estava atribuida (rebalanceamento
  assincrono) no momento da publicacao. Pre-existente a correcao do Critical (confirmado rodando o
  publisher original).

**Correcao:** substituir o consumer manual por um `@KafkaListener` de captura, com
`ContainerTestUtils.waitForAssignment(container, 1)` no `@BeforeEach` para bloquear ate o listener
ter a particao atribuida antes de publicar. O publisher e chamado direto (nao via `@Scheduled`),
isolando o fluxo de publicacao da janela de agendamento. Padrao idiomatico do spring-kafka-test,
alinhado ao consumer de pagamento (`PagamentoStatusConsumerIntegracaoTest`).

**Ajuste do Makefile:** `test-integration` agora faz `down -v` antes do `up`, recriando o volume do
Postgres a cada run. Evita falsos positivos por schema sujo de runs anteriores.

Resultado: 8/8 testes de integracao verdes; 46 unitarios verdes; 0 violacoes Checkstyle/Spotless.

## Como reproduzir / validar

```bash
cd services/assinatura
make verify          # lint + unitarios + package, sem integracao (0 violacoes)
make test-integration  # sobe Postgres na 5433; valida repositorio + publisher (EmbeddedKafka)
```
