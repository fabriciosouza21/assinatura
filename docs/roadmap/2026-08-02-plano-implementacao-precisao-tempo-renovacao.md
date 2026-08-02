# Plano de implementação — precisão de tempo na renovação

**Versão alvo:** `0.3.0` (release ainda não fechado — a mudança entra antes do `release/0.3.0`, sem contrato publicado quebrando)
**Branch base:** `develop`
**Data:** 2026-08-02
**PRD:** `docs/prd/2026-08-02-renovacao-precisao-tempo.md`
**Nomenclatura:** continua a numeração BE-1…BE-13 (0.3.0). BE-14/15 pertencem à listagem (0.4.0). Este plano usa **BE-16 a BE-19, INT-1 e DOCS-4**.

> **Como usar:** cada track em §4 é autocontida e vira um PR próprio com menos
> de 500 linhas. Um agent lê este doc, o PRD, o diagrama de renovação e o
> `AGENTS.md`, implementa com TDD e verifica com `make verify` antes do merge.

---

## 0. Premissa

O domínio de renovação (0.3.0) está em `develop` e o release não foi fechado
(poms em `0.2.0`, sem tag). A precisão de tempo altera apenas o Assinatura
Service: entidade, scheduler, consulta e contrato do campo `proximaRenovacaoEm`.
Nenhuma mudança no Pagamento Service nem nos eventos Kafka.

Hoje, com `APP_RENOVACAO_CICLO_MS` < 1 dia, `proxima_renovacao_em` (DATE)
trunca para "hoje" e a assinatura fica permanentemente em `EM_RENOVACAO`
(ATIVA dura ~1s). O objetivo é `proxima_renovacao_em` virar `timestamptz`/
`Instant`, fazendo a janela `ATIVA` durar exatamente `cicloMs` reais.

---

## 1. Decisões de arquitetura (travadas no PRD)

| ID | Decisão | Racional |
|----|---------|----------|
| D-T1 | **Campo `proximaRenovacaoEm` vira `Instant`** (coluna `timestamptz`, migration `V10` com `USING date::timestamptz`) | Consistente com a outbox e os carimbos dos eventos. |
| D-T2 | **Instante = processamento + cicloMs** | `Instant.now(clock).plusMillis(cicloMs)` na ativação e na aprovação. `fim_ciclo`/`inicio_ciclo` continuam DATE (dedup por `ciclo_referencia`). |
| D-T3 | **Scheduler compara `proxima_renovacao_em <= agora`** | Mantém `FOR UPDATE SKIP LOCKED` e a retomada de vencidos. |
| D-T4 | **API expõe `proximaRenovacaoEm` como `date-time` ISO-8601** | Formato muda (`date` → `date-time`); entra antes do release, sem contrato publicado. |

---

## 2. Convenções (herdadas)

- **Branches:** `feat/<nome>` a partir de `develop`, kebab-case, sem acento.
- **Estilo:** Google Java Style; `make format` corrige, `make lint` verifica. Javadoc obrigatório em todo público.
- **Testes:** JUnit 5 + AssertJ, `@DisplayName`, mensagens `.as(...)`. Excluir `@Tag("integration")` do `make verify`.
- **Commits:** Conventional Commits PT-BR, lowercase, sem acento no assunto. Um commit = uma mudança lógica.
- **Migrations:** o Assinatura Service termina em `V9`. BE-16 cria `V10`.
- **Verificação mínima antes de PR:** `make verify` verde no serviço alterado.

---

## 3. Gates

- **Gate 0 — Leitura dos contratos.** `docs/contratos/contrato-eventos-kafka.puml`
  não expõe a data da próxima renovação (confirmar), e `docs/openapi/assinatura.yaml`
  marca `proximaRenovacaoEm` como `date` (será `date-time` na BE-19). Conferir
  também `bruno/README.md` §Perfil de teste rápido: o texto "vence no próprio
  dia" deixa de valer. É doc; pode ser feito imediatamente.

---

## 4. Tracks (sequenciais — o tipo muda em cascata)

### BE-16 — Domínio e schema
- **Serviço:** Assinatura
- **Depende de:** Gate 0
- **Commits:** `feat: adiciona migration da proxima renovacao como timestamp` + `feat: torna proxima renovacao precisa em instante`
- **Definition of Done:**
  - Migration `V10__muda_proxima_renovacao_para_timestamp.sql`:
    `ALTER TABLE assinatura ALTER COLUMN proxima_renovacao_em TYPE timestamptz USING proxima_renovacao_em::timestamptz;`
  - `Assinatura.proximaRenovacaoEm` (`LocalDate` → `Instant`); `ativar(LocalDate,
    LocalDate, Instant)` e `renovar(LocalDate, Instant)` recebem o instante da
    próxima renovação. `fimCiclo`/`inicioCiclo` permanecem `LocalDate`.
  - `AssinaturaTest` ajustado: ativação com ciclo sub-diário define instante no
    futuro; `renovar` idem; cancelamento imediato limpa o campo (null).
- **Verificação:** `make verify` verde; teste de domínio: `ativar` não dispara
  renovação "no dia" quando o instante ainda está no futuro.

### BE-17 — Fluxos de escrita
- **Serviço:** Assinatura
- **Depende de:** BE-16
- **Commit:** `feat: calcula proxima renovacao pelo instante do processamento`
- **Definition of Done:**
  - `ConfirmarPagamentoAdesao`: na ativação, `proximaRenovacaoEm =
    Instant.now(clock).plusMillis(cicloMs)`.
  - `ProcessarRenovacaoResultado`: na aprovação, idem.
  - `ConfirmarPagamentoAdesaoTest`, `ProcessarRenovacaoResultadoTest` e
    `CancelarAssinaturaTest` ajustados (tipos e asserções de instante).
- **Verificação:** ativação com ciclo de 60s não cria renovação imediatamente;
  aprovação volta a `ATIVA` com o novo instante no futuro.

### BE-18 — Scheduler e varredura
- **Serviço:** Assinatura
- **Depende de:** BE-17
- **Commit:** `feat: varre vencimentos por instante na renovacao`
- **Definition of Done:**
  - `AssinaturaRepository.buscarVencidasParaRenovacao(@Param("agora") Instant)`
    com `proxima_renovacao_em <= :agora`.
  - `RenovacaoScheduler.varrerVencimentos` passa `Instant.now(clock)`.
  - `RenovacaoSchedulerTest` ajustado (vencimento por instante, retomada de
    vencidos, dedup por `ciclo_referencia` intacto).
- **Verificação:** assinatura com instante no passado é retomada no primeiro
  sweep; com instante no futuro não é selecionada.

### BE-19 — Contrato de consulta e OpenAPI
- **Serviço:** Assinatura
- **Depende de:** BE-18
- **Commits:** `feat: expoe proxima renovacao como datetime na consulta` + `docs: atualiza openapi da consulta de assinatura`
- **Definition of Done:**
  - `AssinaturaResponse.proximaRenovacaoEm` (`LocalDate` → `Instant`) e
    Javadoc do parâmetro atualizado.
  - `ConsultaAssinaturaControllerTest` ajustado (asserção de datetime ISO-8601).
  - `docs/openapi/assinatura.yaml`: campo `proximaRenovacaoEm` com `format:
    date-time` e exemplos (`2026-08-02T12:00:00Z`).
- **Verificação:** `GET /assinaturas/{uuid}` devolve `proximaRenovacaoEm` em
  ISO-8601 e `fimCiclo`/`inicioCiclo` continuam `date`.

### INT-1 — Testes de integração
- **Serviço:** Assinatura
- **Depende de:** BE-19
- **Commit:** `test: adapta integracao da renovacao ao instante`
- **Definition of Done:**
  - `RenovacaoSchedulerIntegracaoTest` e `RenovacaoResultadoConsumerIntegracaoTest`
    ajustados aos novos tipos e à janela de ciclo sub-diário.
- **Verificação:** `make test-integration` verde (recria o Postgres na porta
  5433 — **derruba dados de dev**; rodar com o compose parado ou ciente da perda).

### DOCS-4 — Documentação e perfil rápido
- **Serviço:** Assinatura (docs)
- **Depende de:** BE-19
- **Commit:** `docs: atualiza perfil rapido da renovacao apos precisao de tempo`
- **Definition of Done:**
  - `bruno/README.md`: fluxo de aprovação passa a "aprovar → conferir `ATIVA`
    estável → aguardar `EM_RENOVACAO` no ciclo seguinte"; remover o texto
    "o loop só para quando você para de aprovar".
  - `.env.example`: comentário do `APP_RENOVACAO_CICLO_MS` atualizado (ciclo
    sub-diário = janela `ATIVA` real de `cicloMs`).
  - `README.md` e `CHANGELOG.md`: nota sobre a precisão de tempo (sem bump —
    entra no 0.3.0).
- **Verificação:** leitura do Bruno guide reproduz os passos do fluxo.

---

## 5. Mapa de sequenciamento

```
Gate 0 (docs)
   │
   ▼
BE-16 domínio/schema ──> BE-17 escrita ──> BE-18 scheduler ──> BE-19 contrato
   └──────────────────────────────────────────────────────────────> INT-1
                                                                     │
                                                                     ▼
                                                               DOCS-4 ──> release/0.3.0
```

- **Sequencial por decisão:** o tipo muda em cascata (entidade → callers →
  query → contrato). Não há streams paralelos; cada track é um PR < 500 linhas.

---

## 6. Convergência e definition of done

1. Mergear BE-16 → BE-17 → BE-18 → BE-19 → INT-1 → DOCS-4 em `develop`.
2. **Fluxo E2E via Bruno** com o perfil rápido (`.env`):
   - Adesão: `fluxo/assinatura/` → `ATIVA` com `proximaRenovacaoEm` em datetime.
   - Aprovação: aguardar ~60s → `EM_RENOVACAO` → `fluxo/renovacao/` aprovar →
     `ATIVA` **estável por ~60s** → `EM_RENOVACAO` de novo (janela visível).
   - Esgotamento: 3 recusas → `SUSPENSA` (comportamento preservado).
3. O `release/0.3.0` (bump 0.3.0 + CHANGELOG) incorpora esta mudança junto com
   a renovação já em `develop`.

---

## 7. Notas para os agentes (handoff)

- **Leia antes:** este doc, o PRD, `docs/renovacao/renovacao-automatica-assinatura.puml`, o `AGENTS.md`.
- **Pegue UMA track.** Codifique contra o PRD, não contra suposições.
- **TDD:** escreva o teste que reproduz o comportamento antes do código de produção.
- **Verifique:** `make verify` no serviço alterado. Integração (`@Tag("integration")`)
  só no fim, via `make test-integration` — que recria o Postgres de dev.
- **Não mexa** nos contratos travados (eventos Kafka, demais endpoints) sem bump de versão.
- **Commits pequenos**, um por mudança lógica; `make lint` roda no pre-commit.

---

## Ordem dos Entregaveis

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 1 | Domínio e schema (BE-16, migration V10) | — | [x] |
| 2 | Fluxos de escrita (BE-17) | 1 | [x] |
| 3 | Scheduler e varredura (BE-18) | 2 | [x] |
| 4 | Contrato de consulta + OpenAPI (BE-19) | 3 | [x] |
| 5 | Testes de integração (INT-1) | 4 | [x] |
| 6 | Documentação e perfil rápido (DOCS-4) | 5 | [x] |
