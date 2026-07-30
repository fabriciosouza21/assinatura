# PRD: Publicacao de AssinaturaSolicitada via outbox

**Date:** 2026-07-30
**Status:** Draft
**Entrega:** BE-3 do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md` (Track A do plano paralelo `docs/roadmap/2026-07-30-plano-implementacao-paralela.md`)
**Contrato:** `docs/contrato-eventos-kafka.puml` (evento `AssinaturaSolicitada`)
**Design:** `docs/outbox-assinatura-solicitada.puml`, `docs/outbox-modelo-dados.puml`

## Problem

O BE-2 entregou a solicitacao sincrona (`POST /assinaturas`), mas o resultado do
pedido ainda fica preso no Assinatura Service: nada avisa o Pagamento Service de
que existe uma assinatura `AGUARDANDO_PAGAMENTO` para cobrar. Sem essa ponte, o
fluxo downstream (criacao de cobranca, ativacao) nao comeca.

Publicar direto no Kafka apos o `INSERT` da assinatura e um dual-write sem
arranjo de ordem seguro: `publica->commit` abre risco de cobranca fantasma no
gateway (evento publicado de uma assinatura cuja transacao voltou); `commit->publica`
deixa a assinatura travada em `AGUARDANDO_PAGAMENTO` sem auto-cura, ja que um
retry do cliente devolve `409`. A outbox torna o `INSERT` da assinatura e o
registro do evento atomicos na mesma transacao; um publisher separado drena a
outbox para o Kafka com entrega pelo menos uma vez.

## Background

O contrato de eventos travado (`docs/contrato-eventos-kafka.puml`) define
`AssinaturaSolicitada` no topico `assinatura-solicitada` (key `assinaturaId`),
produzido pelo Assinatura Service com entrega via outbox (transacional,
at-least-once) e consumido pelo Pagamento Service, que deduplica por `eventId`.

O diagrama `docs/outbox-assinatura-solicitada.puml` detalha o fluxo em duas
fases: gravacao atomica (assinatura + evento `PENDENTE` na mesma tx) e
publicacao assincrona (`SELECT ... FOR UPDATE SKIP LOCKED`, retry com backoff +
jitter, `FALHA` como DLQ persistida apos 3 tentativas). O modelo de dados
`docs/outbox-modelo-dados.puml` define a tabela `outbox` e o indice de polling
parcial `(status, proxima_tentativa_em, criado_em) WHERE status = 'PENDENTE'`.

O BE-2 ja deixou o enum `Plano` com `valor()` em `BigDecimal` (BASICO 19.90,
PREMIUM 39.90, FAMILIA 59.90), pronto para compor o evento. A decisao D1 do
plano paralelo fixa a outbox no Assinatura Service; a D7 determina DTOs
duplicados por servico (sem modulo compartilhado).

## Requirements

### Must Have

- A solicitacao de assinatura grava um evento `AssinaturaSolicitada` na tabela
  `outbox` em status `PENDENTE`, na **mesma transacao** do `INSERT` da
  assinatura. Se a transacao voltar, nenhuma das duas e persistida.
- O evento carrega os campos do contrato: `eventId` (UUID), `ocorridoEm`
  (Instant), `assinaturaId` (uuid publico da assinatura), `usuarioId` (uuid
  publico do usuario), `plano` e `valor`. O `valor` e derivado de
  `Plano.valor()` ao montar o evento; nao e persistido na assinatura.
- Um `OutboxPublisher` (`@Scheduled`) drena a outbox: seleciona eventos
  `PENDENTE` prontos para envio, publica `AssinaturaSolicitada` no topico
  `assinatura-solicitada` (key = `assinaturaId`) e marca o evento como
  `PUBLICADO` (`publicado_em` preenchido).
- Em falha de publicacao, incrementa `tentativas`, registra `ultimo_erro` e
  reagenda com backoff exponencial + jitter. Apos 3 tentativas, marca o evento
  como `FALHA` (`falhou_em` preenchido) — DLQ persistida para reprocessamento
  manual.
- O topico `assinatura-solicitada` e declarado por um bean `NewTopic` (Kafka
  Admin); nao se depende de auto-create.
- Entrega pelo menos uma vez. A deduplicacao por `eventId` e responsabilidade do
  consumidor (fora do BE-3).

### Should Have

- Polling com `SELECT ... FOR UPDATE SKIP LOCKED`, permitindo mais de uma
  instancia do publisher sem processar a mesma linha.
- Indice de polling parcial `(status, proxima_tentativa_em, criado_em) WHERE
  status = 'PENDENTE'` para eficiencia.
- Parametros de publicacao externalizados por configuracao (intervalo de
  polling, tamanho do lote, maximo de tentativas, base de backoff).

### Out of Scope

- Consumer de `PagamentoStatusAtualizado` e ativacao/recusa da assinatura
  (BE-4 / Track B).
- Login e autenticacao de cliente (FF-1 / Track C).
- Pagamento Service: DB, consumer de `AssinaturaSolicitada`, chamada ao gateway
  e webhook (Tracks P-0, D, E).
- Alteracao do schema da assinatura. O `valor` vem do enum; nao se adiciona
  coluna `valor` nem `criado_em` a `assinatura`.
- Header `Idempotency-Key` no `POST /assinaturas` (BE-5). A protecao contra
  duplicidade do lado do cliente continua sendo a regra "um usuario, uma
  assinatura aberta".
- Interface humana de reprocessamento de eventos `FALHA`.

## Constraints

- O schema e Flyway-owned (`ddl-auto: validate`). A tabela `outbox` exige uma
  nova migration `V4`.
- O contrato `docs/contrato-eventos-kafka.puml` esta travado; campos e tipos do
  evento nao mudam sem bump de versao.
- DTOs duplicados por servico (D7): `AssinaturaSolicitada` em
  `com.globo.assinatura.messaging.event`, serializacao String/JSON via Jackson.
- Payload `JSONB` na tabela `outbox`.
- Convencoes do projeto: Javadoc obrigatorio em todo publico, Google Java Style,
  CQRS Lite. A gravacao da outbox acontece dentro do command `SolicitarAssinatura`.

## Decisoes

- **Outbox no Assinatura Service.** Resolvido (D1). O `INSERT` da assinatura e o
  registro do evento tornam-se atomicos na mesma transacao, eliminando o
  dual-write e seus arranjos de ordem inseguros.
- **Origem do `valor`.** Resolvido. Derivado de `Plano.valor()` ao montar o
  evento. O schema de `assinatura` permanece inalterado (sem coluna `valor`),
  divergindo do rascunho `outbox-modelo-dados.puml`, que a desenha — optou-se
  por nao duplicar dado ja existente no enum.
- **Politica de retry.** Resolvido. Ate 3 tentativas com backoff exponencial +
  jitter; `FALHA` apos a terceira e uma DLQ persistida para reprocessamento
  manual (sem repubicacao automatica).
- **Polling.** Resolvido. `@Scheduled` (fixedDelay), intervalo e tamanho do lote
  configuraveis; defaults de operacao local. `FOR UPDATE SKIP LOCKED` para
  multi-instancia.

## Acceptance Criteria

### Gravacao atomica
- Given um usuario valido sem assinatura aberta, when envia `POST /assinaturas`,
  then a assinatura e um evento `PENDENTE` na outbox sao gravados na mesma
  transacao; se a transacao falhar, nenhuma das duas aparece na base.

### Publicacao bem-sucedida
- Given um evento `PENDENTE` na outbox, when o publisher executa, then
  `AssinaturaSolicitada` e publicada no topico `assinatura-solicitada` com key
  `assinaturaId` e o evento passa a `PUBLICADO` (`publicado_em` preenchido).

### Retry em falha de publicacao
- Given uma falha ao publicar, when ha tentativas restantes (< 3), then o evento
  permanece `PENDENTE`, `tentativas` e incrementado e `proxima_tentativa_em` e
  reagendada com backoff + jitter.

### DLQ persistida apos esgotar tentativas
- Given tres falhas consecutivas de publicacao, when a terceira falha, then o
  evento passa a `FALHA` (`falhou_em` preenchido, `ultimo_erro` registrado) e
  nao e republicado automaticamente.

### Valor do evento
- Given a solicitacao de um plano (ex.: `PREMIUM`), when o evento e publicado,
  then o payload carrega `valor` igual a `Plano.valor()` (ex.: `39.90`).

### Multi-instancia do publisher
- Given duas instancias do publisher, when executam o polling concorrentemente,
  then nenhuma linha da outbox e processada por ambas (`FOR UPDATE SKIP LOCKED`).

### Entrega pelo menos uma vez
- Given uma publicacao confirmada seguida de falha do processo antes do
  `COMMIT`, when o evento e reprocessado, then ele e republicado; o consumidor
  deduplica por `eventId`.
