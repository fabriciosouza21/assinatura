# Plano de implementação — listagem de assinaturas com cache Redis 0.4.0

**Versão alvo:** `0.4.0`
**Branch base:** `develop`
**Data:** 2026-08-02
**PRD:** `docs/prd/2026-08-02-listagem-assinatura-cache.md`
**Design de referência:** `docs/assinatura/listagem-cache-redis.puml`
**Contrato:** `docs/openapi/assinatura.yaml` (já atualizado com `GET /assinaturas`)
**Nomenclatura:** continua a do 0.3.0 (BE-1…BE-13, DOCS-2). Esta release cobre BE-14, BE-15 e DOCS-3.

> **Como usar:** cada track em §4 é autocontida. Um agent lê este doc, o PRD, o
> diagrama `listagem-cache-redis.puml` e o `AGENTS.md`, implementa com TDD e
> verifica com `make verify` antes do merge.

---

## 0. Premissa: o 0.3.0 está fechado

A listagem reusa o domínio e a infraestrutura do 0.3.0 (renovação), já em `develop`:

- **Status completo do ciclo de vida**: `AGUARDANDO_PAGAMENTO`, `ATIVA`,
  `PAGAMENTO_RECUSADO`, `EM_RENOVACAO`, `SUSPENSA`, `CANCELADA`
  (`StatusAssinatura.java`).
- **Consumers de evento** com dedup por `eventId` (`ProcessarPagamento`,
  `ProcessarRenovacaoResultado`) e **scheduler de vencimentos**
  (`RenovacaoScheduler`) com `FOR UPDATE SKIP LOCKED`.
- **Redis já disponível** no docker-compose e usado para sessão JWT
  (`spring-boot-starter-session-data-redis`). O `spring-data-redis` e o
  Lettuce já estão no classpath via esse starter; **nenhum starter novo é
  necessário** para o cache de dados.

---

## 1. Decisões de arquitetura (travadas)

| ID | Decisão | Racional |
|----|---------|----------|
| D-L1 | **Chave de cache versionada**: `assinatura:list:{usuarioUuid}:v{n}:{page}` + contador `assinatura:list:versao:{usuarioUuid}` | A chave é por usuário e página (PRD). O `@CacheEvict` do Spring não apaga por padrão (só chave exata), então o cache é manual com `RedisTemplate`. A invalidação vira um `INCR` no contador: atômico, O(1), sem `SCAN`/`DEL` por padrão, e elimina a corrida de repopulação pós-DEL (a página velha vira inalcançável, expira no TTL). |
| D-L2 | **Invalidação no write, após o commit**, nos 5 fluxos | Solicitação (`SolicitarAssinatura`), status de pagamento (`ProcessarPagamento`), renovação/suspensão (`ProcessarRenovacaoResultado`), scheduler (`RenovacaoScheduler`) e futuro cancelamento HTTP. Invalidação registrada como after-commit (`TransactionSynchronization`): transação que falhou não invalida; `INCR` que falhar é coberto pelo TTL de 5 min. |
| D-L3 | **Cache-aside com degradação silenciosa** | Exceção do Redis na leitura vira cache-miss (segue ao banco, log `WARN`). Cache indisponível não derruba a listagem. |
| D-L4 | **Ordenação por `id DESC`** | O `id` é monotônico com a criação; cobre "mais recente primeiro" e o fallback de assinaturas sem `dataInicio` (`AGUARDANDO_PAGAMENTO`). |
| D-L5 | **TTL de 5 minutos, configurável** | Rede de segurança para falha de invalidação. Consistência eventual aceita em segundos nos fluxos Kafka. |

---

## 2. Convenções (herdadas das releases anteriores)

- **Branches:** `feat/<nome>` a partir de `develop`, kebab-case, sem acento.
- **Estilo:** Google Java Style; `make format` corrige, `make lint` verifica. Javadoc obrigatório em todo público.
- **Testes:** JUnit 5 + AssertJ, `@DisplayName`, mensagens `.as(...)`. Excluir `@Tag("integration")` do `make verify`.
- **Commits:** Conventional Commits PT-BR, lowercase, sem acento no assunto. Um commit = uma mudança lógica.
- **Migrations:** o Assinatura Service termina em `V9`. BE-14 cria `V10` (índice). BE-15 não cria migration.
- **Verificação mínima antes de PR:** `make verify` verde no serviço alterado.

---

## 3. Gates

- **Gate 0 — Diagrama de cache revisado.** `docs/assinatura/listagem-cache-redis.puml`
  existe e documenta o cache-aside e os 5 pontos de invalidação. Antes de
  codificar, conferir que o diagrama reflete a chave versionada (D-L1). É um
  doc; pode ser feito imediatamente.

---

## 4. Tracks (sequenciais)

### BE-14 — Endpoint de listagem paginada (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** Gate 0
- **Commit:** `feat: adiciona listagem paginada de assinaturas`
- **Definition of Done:**
  - `GET /assinaturas` autenticado, com `page` (default 0) e `size` (default 20, máx. 100). Resposta `AssinaturaLista` (`items` + `page` + `size` + `total`), cada item no schema da consulta pontual.
  - Query `ListarAssinaturas` (CQRS Lite): lista as assinaturas do usuário do token, ordenadas por `id DESC`. O `usuarioId` da resposta é o do próprio principal (constante na lista, sem join).
  - `403` para token sem usuário de domínio (administrador), no mesmo molde da solicitação.
  - Migration `V10`: índice para a listagem por usuário com ordenação.
  - `docs/openapi/assinatura.yaml` já contém o contrato; revisar o exemplo/descrição se a implementação divergir.
- **Verificação:** listagem ordenada mais recente primeiro; `page`/`size`/`total` corretos; página além do total retorna `items` vazio; sem assinaturas retorna lista vazia (não 404); `401` sem token; `403` para administrador; usuário nunca vê assinatura de outro.

### BE-15 — Cache Redis com invalidação nos fluxos de escrita (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-14
- **Commit:** `feat: adiciona cache redis com invalidacao na listagem`
- **Definition of Done:**
  - Componente de cache manual com `RedisTemplate` (Lettuce já no classpath) e serialização JSON, sem starter novo. Chave versionada por usuário e página (D-L1), TTL configurável default 5 min (D-L5).
  - `ListarAssinaturas` vira cache-aside: leitura da versão + chave; miss consulta o banco e popula. Exceção do Redis na leitura degrada para o banco com `WARN` (D-L3), nunca `ERROR` da request.
  - Invalidação por `INCR` na versão do usuário, registrada como after-commit, nos 4 pontos de escrita existentes (D-L2): `SolicitarAssinatura`, `ProcessarPagamento`, `ProcessarRenovacaoResultado` (aprovado e esgotado) e `RenovacaoScheduler` (renovação criada e cancelamento por opt-out).
  - Logs Fluent API com `event` estável (`assinatura_lista_cache_hit`, `assinatura_lista_cache_miss`, `assinatura_lista_cache_invalidada`).
  - `docs/assinatura/listagem-cache-redis.puml` atualizado com a chave versionada e commitado.
- **Verificação:** segunda chamada idêntica responde do Redis sem consultar o banco (afirmar ausência de chamada ao repositório); escrita em cada um dos 4 fluxos invalida a versão do usuário; transação que falha não invalida; Redis fora não derruba a listagem; TTL expira a chave.
- **Integração:** cache com Redis real roda em `@Tag("integration")`, via `make test-integration`, no fim da track.

### DOCS-3 — Changelog e versão (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-15
- **Commit:** `chore: bump versao para 0.4.0`
- **Definition of Done:**
  - `services/assinatura/pom.xml` para `0.4.0`.
  - `CHANGELOG.md` com a entrada `[0.4.0]` cobrindo listagem paginada e cache Redis.

---

## 5. Mapa de sequenciamento

```
Gate 0: diagrama revisado (doc)
   │
   ├──> BE-14: endpoint GET /assinaturas (V10)
   │        └──> BE-15: cache Redis + invalidação
   │                └──> DOCS-3: bump 0.4.0 + CHANGELOG
   │
   ▼
Convergência: E2E listagem + release/0.4.0
```

- **Sequencial por decisão:** a feature é pequena e inteira no Assinatura
  Service; não há streams paralelos. BE-15 depende de BE-14 (o cache envolve a
  query da listagem).

---

## 6. Convergência e definition of done (release 0.4.0)

1. Mergear BE-14, BE-15 e DOCS-3 → `develop`.
2. Garantir que o `0.3.0` foi releaseado (bump em ambos os `pom.xml` + `CHANGELOG.md`), pois o `0.4.0` assume o domínio de renovação em produção.
3. **Fluxo E2E:** cadastro → login → `POST /assinaturas` (202) → outbox publica → Pagamento cobra → mock `APPROVED` → webhook → assinatura `ATIVA` → `GET /assinaturas` lista com cache; renovação avança `fimCiclo` e a listagem reflete em segundos; opt-out no vencimento mostra `CANCELADA`.
4. `release/0.4.0`: bump `0.4.0` em `services/assinatura/pom.xml`; atualizar `CHANGELOG.md`.

---

## 7. Notas para os agentes (handoff)

- **Leia antes:** este doc, o PRD de listagem, o diagrama `listagem-cache-redis.puml`, o `AGENTS.md` (estilo/testes/commits).
- **Pegue UMA track.** Codifique contra o PRD e o diagrama, não contra suposições.
- **TDD:** escreva o teste que reproduz o comportamento antes do código de produção (`AGENTS.md` §Scientific TDD).
- **Verifique:** `make verify` no serviço alterado. Integração (`@Tag("integration")`) só no fim, via `make test-integration`.
- **Não mexa** nos contratos travados (eventos Kafka, endpoints existentes) sem bump de versão.
- **Commits pequenos**, um por mudança lógica; `make lint` roda no pre-commit.

---

## Ordem dos Entregaveis

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 1 | Listagem paginada de assinaturas (`GET /assinaturas`) | — | [ ] |
| 2 | Cache Redis na listagem com invalidação nos fluxos de escrita | 1 | [ ] |
| 3 | Versão 0.4.0 e CHANGELOG | 2 | [ ] |
