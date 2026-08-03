# Roadmap: Entrega consolidada do desafio de sistema de assinaturas

**Data:** 2026-08-02
**Versão alvo:** `0.5.0`
**Branch base:** `develop`
**PRDs de referência:** `docs/prd/2026-08-02-fechamento-lacunas-desafio.md` e
`docs/prd/2026-08-02-dlq-recuperacao-observabilidade.md`

O desafio exige um sistema de assinaturas com adesão, renovação automática no
dia do vencimento, suspensão após três cobranças recusadas e cancelamento que
mantém o acesso até o fim do ciclo. Este roadmap consolida a entrega do início
ao fim: o que já está em `develop` (marcado como concluído) e o que falta
(lacunas de robustez, DLQ e fechamento de release, marcados como pendentes ou em
andamento).

Todas as regras obrigatórias do enunciado já estão implementadas:

- **Uma assinatura por vez.** Índice único parcial em
  `(usuario_id) WHERE status IN ('AGUARDANDO_PAGAMENTO','ATIVA','EM_RENOVACAO')`
  com `SELECT ... FOR UPDATE` na transação (BE-2, BE-23).
- **Suspensão após três renovações falhadas.** Três recusas consecutivas
  suspendem a assinatura (BE-10, BE-12, BE-13).
- **Teto de falhas técnicas do gateway.** N falhas técnicas consecutivas
  esgotam a renovação pelo mesmo fluxo das três recusas (BE-20).
- **Renovação automática no vencimento.** Varredura por instante exato com
  `FOR UPDATE SKIP LOCKED` (BE-16 a BE-19).
- **Cancelamento com acesso até o fim do ciclo.** `CancelamentoAgendado` cancela
  no fim do ciclo; demais status cancelam na hora (PR #27).

---

## Backend — Assinatura Service

### Entregues (releases 0.1.0 a 0.4.0)

| ID | Entrega | Release | Status |
|----|---------|---------|--------|
| BE-1 | Cadastro de usuário via `POST /usuarios` com `usuario_uuid` | 0.2.0 | [x] |
| FF-1 | Login de cliente (`/auth/login`) fechando o vínculo `User`↔`Usuario` | 0.2.0 | [x] |
| BE-2 | Solicitação e consulta de assinatura (`202`, `409`, índice único parcial) | 0.2.0 | [x] |
| BE-3 | Outbox: `AssinaturaSolicitada` gravada e publicada na mesma transação | 0.2.0 | [x] |
| BE-4 | Consumer de `PagamentoStatusAtualizado` com ativação/recusa sob lock | 0.2.0 | [x] |
| BE-7 | Outbox roteia evento por tipo, não por tópico fixo | 0.3.0 | [x] |
| BE-8 | Ciclo de renovação no agregado (`inicioCiclo`, `fimCiclo`, renovação por registro) | 0.3.0 | [x] |
| BE-9 | Scheduler varre vencimentos e dispara renovação | 0.3.0 | [x] |
| BE-10 | Consome resultado de renovação: aprovação rola o ciclo, três recusas suspendem | 0.3.0 | [x] |
| Cancelamento | `POST /assinaturas/{uuid}/cancelamento`: fim de ciclo ou imediato | 0.3.0 | [x] |
| JWT obrigatório | Endpoints de assinatura exigem JWT do dono (ADR 0003) | 0.3.0 | [x] |
| BE-14 | Listagem paginada `GET /assinaturas` | 0.4.0 | [x] |
| BE-15 | Cache Redis da listagem com invalidação por versão pós-commit | 0.4.0 | [x] |
| BE-16 a 19 | Precisão de tempo da renovação: domínio, escrita, scheduler por instante e contrato `date-time` | 0.4.0 | [x] |
| ADR 0002 | Pacotes por capacidade com regras ArchUnit (PR #25) | 0.4.0 | [x] |
| BE-23 | `EM_RENOVACAO` entra no índice único de assinatura aberta e na rejeição de nova assinatura (PR #33) | 0.5.0 | [x] |

### Em andamento

### BE-22 — Consulta de assinatura ativa com cache
`feat/consulta-assinatura-ativa-cache`
- O enunciado pede cache para "consultas de assinaturas ativas"; hoje o cache
  cobre a listagem em qualquer status.
- Novo caminho de leitura devolve a assinatura ativa corrente do usuário,
  servida do Redis no caminho quente e do banco no frio, com invalidação por
  versão como a da listagem e degradação silenciosa se o Redis falhar.
- **Estado:** leitura via cache e miss p/ banco commitadas (2 commits); controller
  e teste do endpoint ainda não commitados (untracked).

---

## Backend — Pagamento Service

### Entregues (releases 0.1.0 a 0.4.0)

| ID | Entrega | Release | Status |
|----|---------|---------|--------|
| BE-5 | Consumer de `AssinaturaSolicitada` cria cobrança no gateway com idempotência | 0.2.0 | [x] |
| BE-6 | Webhook de pagamento com HMAC, dedup por `eventId` e publicação de resultado | 0.2.0 | [x] |
| BE-11 | Cobrança de renovação a partir de `RenovacaoSolicitada` | 0.3.0 | [x] |
| BE-12 | Scheduler repete cobrança após recusa; falha técnica não consome tentativa | 0.3.0 | [x] |
| BE-13 | Webhook decide a renovação: aprovada encerra, três recusas esgotam | 0.3.0 | [x] |
| Outbox | Resultados de renovação e cancelamento publicados via outbox com retry e DLQ | 0.3.0 | [x] |
| BE-20 | Teto de falhas técnicas do gateway: N falhas consecutivas esgotam a renovação (PR #32) | 0.5.0 | [x] |

### Em andamento

### BE-21 — Retentativa com backoff no cliente do gateway
`feat/retry-backoff-cliente-gateway`
- O cliente HTTP do gateway passa a aplicar retry com backoff exponencial e
  jitter para falhas transitórias (timeout, 5xx) antes de propagar.
- Falha técnica esgotada continua contando contra o teto da BE-20.
- **Estado:** retry com `Retry.backoff` + wrap em
  `CobrancaGatewayIndisponivelException` ao esgotar commitado (1 commit); ajustes
  finais no client e no teste ainda não commitados.

---

## Infraestrutura compartilhada

### BE-24 — Recuperação automática da outbox
`feat/recuperacao-automatica-outbox`
- Eventos marcados `FALHA` ficam presos no banco para sempre nos dois serviços.
- Um scheduler promove `FALHA` → retentativa após quarentena configurável, com
  limite de ciclos e `FOR UPDATE SKIP LOCKED` para suportar múltiplas
  instâncias.
- **Base pré-existente:** a branch local
  `worktree-feat-outbox-dlq-recovery` tem o scheduler pronto **somente no
  Assinatura**, mas embaralhada com a refatoração de pacotes (336 arquivos).
  Precisa ser isolada por cherry-pick dos 2 commits relevantes (`2f6eaa5`,
  `c9b6167`) para uma branch limpa a partir de `develop`, depois replicada no
  Pagamento. **Gargalo do fluxo:** BE-25, BE-26 e BE-28 dependem desta base.

### BE-25 — Observabilidade de falhas
`feat/observabilidade-dlq`
- Falha definitiva hoje só é detectável lendo log.
- Métrica expõe o número de eventos em `FALHA` na outbox e o volume retido nos
  tópicos `-dlq`, nos dois serviços.

### BE-26 — Recuperação manual assistida da outbox
`feat/recuperacao-manual-outbox`
- Após esgotar os ciclos automáticos, operador lista e retoma eventos em
  `FALHA` sem executar SQL direto no banco, preservando a idempotência.

### BE-27 — Replay da DLQ de consumer
`feat/replay-dlq-consumer`
- Mensagens dos tópicos `-dlq` dos consumers ficam paradas sem caminho de
  reprocessamento.
- Replay sob controle operacional reprocessa pelo fluxo original preservando a
  idempotência por `event_id`.

### BE-28 — Configuração operacional dos DLTs e logging
`feat/config-operacional-dlt`
- Partitions, fator de replicação e retenção dos tópicos de DLQ definidos
  explicitamente nos dois serviços, em vez de herdarem defaults do broker.
- Alinhar nível (WARN) e chaves estruturadas das falhas de outbox entre os
  serviços (hoje assinatura usa DEBUG/`attempt`, pagamento WARN/`tentativa`).

---

## Qualidade e governança

### INT-2 — Testes de integração autossuficientes
`test: adota testcontainers nos testes de integracao`
- Testes de banco dependem do Postgres do `docker-compose.yml` na porta 5433.
- Passam a subir Postgres descartável via Testcontainers, sem configuração
  manual e sem depender de base pré-populada.

### DOCS-5 — Changelog e versão
`release/0.5.0`
- Alinhar versões dos dois `pom.xml` (hoje Assinatura em `0.4.0` e Pagamento em
  `0.2.0`) e do `CHANGELOG.md` com tudo que está no trunk.

---

## Ordem dos Entregaveis

Convenção: `[x]` no `develop` · `[~]` em andamento (worktree ativa) · `[ ]` pendente.

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 1 | Teto de falhas técnicas do gateway (BE-20) | — | [x] PR #32 |
| 2 | Conformidade da assinatura única durante renovação (BE-23) | — | [x] PR #33 |
| 3 | Retry com backoff no cliente do gateway (BE-21) | 1 | [~] worktree |
| 4 | Consulta de assinatura ativa com cache (BE-22) | — | [~] worktree |
| 5 | Recuperação automática da outbox nos dois serviços (BE-24) | — | [ ] |
| 6 | Replay da DLQ de consumer (BE-27) | — | [ ] |
| 7 | Observabilidade de falhas (BE-25) | 5 | [ ] |
| 8 | Recuperação manual assistida da outbox (BE-26) | 5 | [ ] |
| 9 | Configuração operacional dos DLTs e logging (BE-28) | 5, 6 | [ ] |
| 10 | Testes de integração com Testcontainers (INT-2) | 3-9 | [ ] |
| 11 | Changelog + bump versão 0.5.0 (DOCS-5) | 3-10 | [ ] |

**Paralelismo:** as frentes 3 e 4 já estão abertas em worktrees disjuntas
(pagamento/gateway e assinatura/consulta). Assim que uma delas fecha, abrir a 5
(BE-24), que é o gargalo das 7, 8 e 9 por tocar `shared/outbox` nos dois
serviços. A 6 (BE-27) é independente e pode abrir a qualquer momento, pois toca
`shared/kafka` (replay de DLQ de consumer), área distinta da outbox.

**Gargalo de migração:** Assinatura em V12, Pagamento em V9 (após BE-20, V10).
BE-24 pode precisar de migration em ambos os serviços se o status
`RETENTATIVA_DLQ` exigir coluna. Reservar V13 (Assinatura) e V11 (Pagamento) ao
abrir as frentes, para evitar quebra de baseline no merge.
