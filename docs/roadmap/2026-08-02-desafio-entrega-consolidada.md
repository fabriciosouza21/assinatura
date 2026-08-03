# Roadmap: Entrega consolidada do desafio de sistema de assinaturas

**Data:** 2026-08-02
**Versão alvo:** `0.5.0`
**Branch base:** `develop`
**PRDs de referência:** `docs/prd/2026-08-02-fechamento-lacunas-desafio.md`,
`docs/prd/2026-08-02-dlq-recuperacao-observabilidade.md`,
`docs/prd/2026-08-03-recuperacao-automatica-outbox.md` (BE-24) e os PRDs
focados de BE-20, BE-21, BE-22 e BE-23 em `docs/prd/`.

O desafio exige um sistema de assinaturas com adesão, renovação automática no
dia do vencimento, suspensão após três cobranças recusadas e cancelamento que
mantém o acesso até o fim do ciclo. Este roadmap consolida a entrega do início
ao fim: o que já está em `develop` (marcado como concluído) e o que falta
(recuperação manual, observabilidade, replay de DLQ de consumer, configuração
de DLTs e fechamento de release, marcados como pendentes). Todas as lacunas de
robustez e aderência ao enunciado foram fechadas nos PRs #32 a #36.

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
| BE-22 | Consulta de assinatura ativa via cache (caminho quente Redis, frio banco) com cache de ausência e invalidação por versão (PR #34) | 0.5.0 | [x] |

### Pendentes

_Nenhuma frente aberta no Assinatura Service._

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
| BE-21 | Retry com backoff exponencial + jitter no cliente do gateway, só para falhas transitórias (PR #36) | 0.5.0 | [x] |

### Pendentes

_Nenhuma frente aberta no Pagamento Service._

---

## Infraestrutura compartilhada

### Entregues

| ID | Entrega | Release | Status |
|----|---------|---------|--------|
| BE-24 | Recuperação automática da outbox nos dois serviços: scheduler promove `FALHA` → `RETENTATIVA_DLQ` após quarentena, com limite de ciclos e `FOR UPDATE SKIP LOCKED` (PR #35) | 0.5.0 | [x] |

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
- Mensagens dos tópicos `-dlq` dos consumers ficam retidas no Kafka após esgotar
  as retentativas do `DefaultErrorHandler`.
- Um `@KafkaListener` próprio consome os `*-dlq`, desligado por padrão
  (`autoStartup` via `APP_KAFKA_REPLAY_AUTO_STARTUP`), e republica a mensagem no
  tópico original lido do header `kafka_dlt-original-topic`. O BackOff do listener
  de replay é longo (default 1h), distinto do retry curto dos consumers normais.
- Os guards de idempotência existentes (`eventId` e chave natural) absorvem o
  reprocessamento. Não há caminho automático pela outbox: a DLQ de consumer é
  falha de consumo, disjunta da falha de publicação tratada pelo BE-24.

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
| 3 | Retry com backoff no cliente do gateway (BE-21) | 1 | [x] PR #36 |
| 4 | Consulta de assinatura ativa com cache (BE-22) | — | [x] PR #34 |
| 5 | Recuperação automática da outbox nos dois serviços (BE-24) | — | [x] PR #35 |
| 6 | Replay da DLQ de consumer (BE-27) | — | [ ] |
| 7 | Observabilidade de falhas (BE-25) | 5 | [ ] |
| 8 | Recuperação manual assistida da outbox (BE-26) | 5 | [ ] |
| 9 | Configuração operacional dos DLTs e logging (BE-28) | 5, 6 | [ ] |
| 10 | Testes de integração com Testcontainers (INT-2) | 3-9 | [ ] |
| 11 | Changelog + bump versão 0.5.0 (DOCS-5) | 3-9 | [ ] |

**Próxima frente:** BE-27 (item 6) é o único independente que pode abrir agora,
pois toca `shared/kafka`, área distinta da outbox. BE-25 e BE-26 (itens 7, 8)
dependem do BE-24 (já entregue) e também podem abrir em paralelo entre si em
worktrees separadas por serviço, pois ambas tocam `shared/outbox`. Em sequência
fica BE-28, que precisa do BE-24 e do BE-27 landados para alinhar logging e
configurar os tópicos DLT.

**Numeração de migrations (após BE-20 e BE-24):** Assinatura em V13, Pagamento
em V11. Próximo número livre: V14 (Assinatura) e V12 (Pagamento). Coordenar ao
abrir frentes que toquem DB no mesmo serviço.
