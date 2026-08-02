# PRD: Precisão de tempo na próxima renovação

**Date:** 2026-08-02
**Status:** Draft
**Contrato:** `docs/openapi/assinatura.yaml` (atualizar campo `proximaRenovacaoEm`)
**Serviço:** Assinatura Service (domínio, scheduler e consulta)

## Problem

Com ciclo de renovação menor que um dia (perfil rápido de teste), a assinatura
fica **sempre em `EM_RENOVACAO`**: `proxima_renovacao_em` é `DATE`, e um ciclo
sub-diário trunca para "hoje" (`Duration.toDays()` = 0). A ativação marca a
renovação como vencida no próprio dia, o scheduler a pega no sweep seguinte
(~1s) e a aprovação da renovação rola o ciclo para "hoje" de novo. O status
`ATIVA` dura menos de um segundo: impossível demonstrar o fluxo de aprovação
— o usuário aprova e a assinatura já está em `EM_RENOVACAO`.

Além da demo, a semântica de produção perde precisão: a renovação não dispara
no instante exato do fim do ciclo, e sim no primeiro sweep do dia do
vencimento (ou no próprio dia, para ciclos sub-diários).

## Background

O domínio de renovação (0.3.0, PRD `2026-07-31-dominio-ciclo-renovacao.md`)
guarda `inicio_ciclo`, `fim_ciclo` e `proxima_renovacao_em` como `DATE`. O
ciclo é configurável por `APP_RENOVACAO_CICLO_MS` (default 30 dias), e o
scheduler varre vencimentos com `proxima_renovacao_em <= hoje` (`FOR UPDATE
SKIP LOCKED`).

O ciclo do scheduler usa apenas `fim_ciclo` (DATE) como chave de dedup
(`renovacao.ciclo_referencia`), e o contrato dos eventos Kafka não expõe a
data da próxima renovação. A precisão de hora afeta apenas o disparo da
renovação e a consulta `GET /assinaturas/{uuid}`.

O perfil rápido atual (documentado em `bruno/README.md` e `.env.example`)
funciona para o esgotamento (3 recusas suspendem), mas torna o fluxo de
aprovação inobservável: a janela `ATIVA` é de ~1s.

## Requirements

### Must Have

- `proxima_renovacao_em` passa a ser **timestamp com fuso** (coluna
  `timestamptz`, campo `Instant`), com migration Flyway `V10`.
- **Ativação**: `proximaRenovacaoEm = instante do processamento + cicloMs`.
  Com ciclo sub-diário, a renovação só dispara no instante exato do fim do
  ciclo; a assinatura fica `ATIVA` por `APP_RENOVACAO_CICLO_MS` reais.
- **Aprovação de renovação**: idem — o novo ciclo vence
  `instante da aprovação + cicloMs`.
- **Scheduler**: seleção por `proxima_renovacao_em <= agora` (`Instant`),
  mantendo `FOR UPDATE SKIP LOCKED` e o comportamento "renovações atrasadas
  são retomadas no próximo ciclo".
- `inicio_ciclo` e `fim_ciclo` **continuam `DATE`**: são datas de calendário
  do ciclo e a chave de dedup (`ciclo_referencia`) não muda.
- `GET /assinaturas/{uuid}` expõe `proximaRenovacaoEm` como **datetime
  ISO-8601** (ex.: `2026-08-02T12:00:00Z`). *Quebra de formato do campo.*
- Ciclo de 30 dias (produção) mantém o comportamento atual: a renovação
  dispara no dia do vencimento (primeiro sweep após o instante exato).

### Should Have

- Atualizar `docs/openapi/assinatura.yaml` (formato `date-time`).
- Atualizar os fluxos do Bruno e os comentários do `.env.example` que
  descrevem o loop "vence no próprio dia".
- O fluxo de renovação do Bruno passa a ser: aprovar → conferir `ATIVA`
  estável → aguardar `EM_RENOVACAO` no próximo ciclo.

### Out of Scope

- Tornar `inicio_ciclo`/`fim_ciclo` timestamp. A chave de dedup por ciclo
  continua sendo data.
- Mudar o contrato dos eventos Kafka (`PagamentoRenovacaoAprovado`,
  `RenovacaoTentativasEsgotadas` etc.).
- Scheduler com janelas/agendamento por hora ou dia da semana.
- Alterar a regra de suspensão (3 tentativas) ou o opt-out.
- Pagamento Service: nenhuma mudança.

## Constraints

- `ddl-auto: validate`: o schema é Flyway-owned; a migration `V10` é
  obrigatória e precisa do `USING` para converter `date` → `timestamptz`.
- A regra de negócio de 30 dias não pode mudar: ciclo ≥ 1 dia dispara no dia
  do fim do ciclo, como hoje.
- Contrato da API: o formato do campo `proximaRenovacaoEm` muda
  (`date` → `date-time`). A mudança entra antes do `release/0.3.0`, que ainda
  não foi fechado — não há contrato publicado quebrando.
- CQRS Lite: a consulta continua Query pura; o cálculo do instante mora nos
  Commands/consumers de escrita.

## Decisoes

- **Tipo do campo.** Resolvido. `Instant` em Java + `timestamptz` no banco,
  consistente com a outbox e os carimbos dos eventos.
- **Cálculo do instante.** Resolvido. `Instant.now(clock) + cicloMs` no
  momento do processamento (ativação e aprovação), nunca a partir de
  `fim_ciclo` (que continua DATE).
- **Scheduler.** Resolvido. Comparação `<= agora` por `Instant`, mantendo a
  retomada de vencidos.
- **Contrato da API.** Resolvido. `proximaRenovacaoEm` vira `date-time`
  ISO-8601 com offset; `fimCiclo` e `inicioCiclo` permanecem `date`.

## Acceptance Criteria

### Janela ATIVA com ciclo sub-diário
- Given `APP_RENOVACAO_CICLO_MS=60000` e assinatura `ATIVA`, when a ativação
  é processada, then a renovação não dispara nos sweeps seguintes; when
  `60s` passam, then a assinatura entra em `EM_RENOVACAO` no próximo sweep.

### Aprovação visível
- Given uma assinatura `EM_RENOVACAO`, when a renovação é aprovada (webhook),
  then a assinatura volta a `ATIVA` e permanece por `cicloMs` reais antes de
  entrar em `EM_RENOVACAO` de novo.

### Esgotamento preservado
- Given `APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS=0,0`, when 3 recusas são
  simuladas, then a assinatura é suspensa, sem mudança de comportamento.

### Ciclo de dias preservado
- Given `APP_RENOVACAO_CICLO_MS` = 30 dias, when a assinatura é ativada,
  then `proximaRenovacaoEm` cai no dia do fim do ciclo (primeiro sweep após o
  instante) e a renovação dispara nesse dia.

### Scheduler com vencidos
- Given uma assinatura com `proxima_renovacao_em` no passado (app parada),
  when o app volta e o scheduler roda, then a renovação é retomada no
  primeiro sweep (condição `<=`).

### Contrato da API
- Given `GET /assinaturas/{uuid}`, then `proximaRenovacaoEm` é um datetime
  ISO-8601 (ex.: `2026-08-02T12:00:00Z`) e `fimCiclo`/`inicioCiclo` seguem
  `date` (`2026-08-02`).

## Referencias

- PRD `2026-07-31-dominio-ciclo-renovacao.md`: origem do ciclo e das datas.
- `docs/renovacao/renovacao-automatica-assinatura.puml`: fluxo de renovação.
- `bruno/README.md`: perfil rápido e fluxos de renovação (atualizar).
- `services/assinatura/src/main/resources/application.yaml`: `app.renovacao.ciclo-ms`.
