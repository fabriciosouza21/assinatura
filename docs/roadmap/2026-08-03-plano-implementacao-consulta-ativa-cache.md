# Plano de implementação — consulta da assinatura ativa com cache Redis

**Versão alvo:** `0.5.0` (próxima release após a 0.4.0)
**Branch base:** `develop`
**Data:** 2026-08-03
**PRD:** `docs/prd/2026-08-03-consulta-assinatura-ativa-cache.md`
**Design de referência:** `docs/assinatura/consulta-ativa-cache-redis.puml`
**Contrato:** `docs/openapi/assinatura.yaml` (adicionado `GET /assinaturas/ativa`)

> **Como usar:** cada track em §4 é autocontida. Um agent lê este doc, o PRD, o
> diagrama `consulta-ativa-cache-redis.puml` e o `AGENTS.md`, implementa com TDD e
> verifica com `make verify` antes do merge.

---

## 1. Decisões de arquitetura (travadas)

| ID | Decisão | Racional |
|----|---------|----------|
| D-A1 | **Path dedicado** `GET /assinaturas/ativa`, dono derivado do token (ADR 0003) | Rejeitado `GET /usuarios/{uuid}/assinatura-ativa` por exigir validar o uuid contra o token. Rota literal vence o template `/{uuid}` no Spring MVC. |
| D-A2 | **Sem assinatura ATIVA devolve `404`** | Consistente com `GET /assinaturas/{uuid}`: o recurso "a assinatura ativa" existe ou não existe. Rejeitado `200 null` por ambiguidade no contrato. |
| D-A3 | **`ROLE_ADMIN` recebe `403`** | Mesmo molde da listagem: token sem usuário de domínio (`usuarioId == null`) não tem assinatura própria. Inspecionar a ativa de outro usuário fica fora de escopo. |
| D-A4 | **Reuso do contador de versão da listagem** (`assinatura:list:versao:{usuarioUuid}`) | O PRD deixou a escolha em aberto. Os 5 fluxos de escrita já incrementam esse contador após o commit; reusar invalida a ativa junto com zero mudança nos Commands/consumers. Falso positivo de invalidação é inofensivo em cache-aside (custo: queda de hit rate). |
| D-A5 | **Cache negativo da ausência** | A ausência é cacheada como o literal JSON `"null"` na mesma chave versionada. Usuário novo ou sem ATIVA não martela o banco. Consistente com a listagem, que cacheia página vazia. |
| D-A6 | **Chave de dados dedicada** `assinatura:ativa:v{n}:{usuarioUuid}` + TTL 5 min configurável (`app.cache.assinatura-ativa-ttl`) | Namespace próprio evita colisão com a listagem; o contador é compartilhado (D-A4), mas os dados não. |
| D-A7 | **Contrato tri-estado no wrapper** (`recuperar` devolve `Optional<Optional<AssinaturaResponse>>`) | Externo vazio = miss (chave ausente ou JSON ilegível); externo presente + interno vazio = ausência cacheada; externo presente + interno presente = hit. Nested Optional sem tipo novo; rejeitado interface selada (um único consumidor). |

---

## 2. Convenções (herdadas das releases anteriores)

- **Branches:** `feat/<nome>` a partir de `develop`, kebab-case, sem acento.
- **Estilo:** Google Java Style; `make format` corrige, `make lint` verifica. Javadoc obrigatório em todo público.
- **Testes:** JUnit 5 + AssertJ, `@DisplayName`, mensagens `.as(...)`. Excluir `@Tag("integration")` do `make verify`.
- **Commits:** Conventional Commits PT-BR, lowercase, sem acento no assunto. Um commit = uma mudança lógica.
- **Migrations:** nenhuma — a unicidade de ATIVA por usuário já é garantida pelo índice parcial `uq_assinatura_aberta_usuario` (V3).
- **Verificação mínima antes de PR:** `make verify` verde no serviço alterado.

---

## 3. Gates

- **Gate 0 — Diagrama de cache revisado.** `docs/assinatura/consulta-ativa-cache-redis.puml`
  existe e documenta o cache-aside com cache negativo e o contador compartilhado
  (D-A4). É um doc; pode ser feito imediatamente.

---

## 4. Tracks (sequenciais)

### BE-16 — Endpoint e query da consulta da ativa (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** Gate 0
- **Commit:** `feat: consulta assinatura ativa via cache` (raiz do caminho)
- **Definition of Done:**
  - `GET /assinaturas/ativa` autenticado, dono do token. `200` com o schema
    `AssinaturaResponse` (sem DTO novo), `404` sem assinatura ATIVA,
    `403` para token sem usuário de domínio.
  - Query `ConsultarAssinaturaAtiva` (CQRS Lite): cache-aside; miss consulta
    o banco (`findByUsuarioIdAndStatus(usuarioId, ATIVA)`) e popula.
  - `ConsultaAssinaturaAtivaController` registrado no `assignableTypes` do
    `ConsultaExceptionHandler` (senão 404/403 viram 500).
  - Testes: hit curto-circuita o banco; miss consulta e popula; 404; 403 admin;
    401 sem token (integração).

### BE-17 — Cache Redis com cache negativo (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-16
- **Commit:** `feat: implementa cache da consulta da assinatura ativa` (infra) e
  `feat: cacheia ausencia de assinatura ativa na consulta` (negativo)
- **Definition of Done:**
  - `AssinaturaAtivaCache` espelho da listagem: chave `assinatura:ativa:v{n}:{uuid}`,
    contador compartilhado (D-A4), TTL `app.cache.assinatura-ativa-ttl` (default
    300 s), degradação silenciosa do `CacheVersionado`, logs Fluent
    `assinatura_ativa_cache_miss` / `_hit` / `_json_invalido` / `_populacao_falhou`.
  - Contrato tri-estado (D-A7): ausência gravada como JSON `"null"` e relida como
    externo-presente + interno-vazio; a query responde vazio sem tocar o banco.
  - Nenhuma mudança nos 5 fluxos de escrita (contador compartilhado já invalida).
  - `docs/assinatura/consulta-ativa-cache-redis.puml` atualizado e commitado.
- **Verificação:** segunda chamada idêntica responde do Redis sem consultar o
  banco; usuário sem ativa responde 404 do cache negativo; contador ilegível cai
  na versão zero; JSON ilegível vira miss; Redis fora não derruba a consulta.
- **Integração:** cache com Redis real roda em `@Tag("integration")`, via
  `make test-integration`, no fim da track.

### DOCS-4 — Contrato e changelog (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-17
- **Commit:** `docs: documenta consulta da assinatura ativa no openapi` e
  `chore: bump versao para 0.5.0`
- **Definition of Done:**
  - `docs/openapi/assinatura.yaml` com `GET /assinaturas/ativa`
    (`operationId: consultarAssinaturaAtiva`).
  - `services/assinatura/pom.xml` para `0.5.0`.
  - `CHANGELOG.md` com a entrada `[0.5.0]`.

---

## 5. Mapa de sequenciamento

```
Gate 0: diagrama revisado (doc)
   │
   ├──> BE-16: endpoint GET /assinaturas/ativa
   │        └──> BE-17: cache Redis + negativo
   │                └──> DOCS-4: openapi + bump 0.5.0 + CHANGELOG
   │
   ▼
Convergência: release/0.5.0
```

- **Sequencial por decisão:** BE-17 envolve a query do BE-16 (o wrapper é
  consumido por `ConsultarAssinaturaAtiva`).

---

## 6. Convergência e definition of done (release 0.5.0)

1. Mergear BE-16, BE-17 e DOCS-4 → `develop`.
2. Garantir que o `0.4.0` foi releaseado antes do bump.
3. **Fluxo E2E:** cadastro → login → `POST /assinaturas` (202) → pagamento
   aprovado → `GET /assinaturas/ativa` reflete `ATIVA` em segundos (invalidação
   pelo contador compartilhado); suspensão ou cancelamento faz a consulta voltar
   a `404`; segunda chamada idêntica responde do Redis.
4. `release/0.5.0`: bump `0.5.0` em `services/assinatura/pom.xml`; atualizar
   `CHANGELOG.md`.

---

## 7. Notas para os agentes (handoff)

- **Leia antes:** este doc, o PRD da consulta ativa, o diagrama
  `consulta-ativa-cache-redis.puml`, o `AGENTS.md` (estilo/testes/commits).
- **Pegue UMA track.** Codifique contra o PRD e o diagrama, não contra suposições.
- **TDD:** escreva o teste que reproduz o comportamento antes do código de produção.
- **Verifique:** `make verify` no serviço alterado. Integração (`@Tag("integration")`)
  só no fim, via `make test-integration`.
- **Não mexa** no contador da listagem: ele agora é compartilhado por duas leituras.
- **Commits pequenos**, um por mudança lógica; `make lint` roda no pre-commit.

---

## Ordem dos Entregaveis

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 1 | Endpoint e query da consulta da ativa (`GET /assinaturas/ativa`) | — | [x] |
| 2 | Cache Redis na consulta da ativa com cache negativo | 1 | [x] |
| 3 | Contrato OpenAPI e CHANGELOG | 2 | [x] |

> O bump de versão para `0.5.0` fica para a `release/0.5.0` (padrão da 0.4.0);
> o CHANGELOG entra em `[Não publicado]`.
