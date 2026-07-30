# PRD: Consumo de AssinaturaSolicitada e criacao de cobranca

**Date:** 2026-07-30
**Status:** Draft
**Entrega:** BE-5 do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md` (Track D do plano paralelo `docs/roadmap/2026-07-30-plano-implementacao-paralela.md`)
**Contrato:** `docs/contrato-eventos-kafka.puml`, `docs/mock-meio-pagamento.puml`

## Problem

Com o BE-3 publicando `AssinaturaSolicitada` via outbox, o evento passa a
existir no Kafka, mas ninguem o consome. O Pagamento Service ainda nao fala com
o gateway nem persiste nada: ao receber `AssinaturaSolicitada`, nenhuma cobranca
e criada, nenhuma correlacao `assinaturaId <-> paymentId` existe, e o webhook
(BE-6) nao tera o que notificar. O fluxo para em `AGUARDANDO_PAGAMENTO`. Esta
entrega e o primeiro passo stateful do Pagamento Service: consumir o evento,
criar a cobranca no gateway mock e persistir a correlacao em `PENDING`.

## Background

- BE-5 corresponde a Track D do plano de implementacao paralela. No fluxo
  paralelo, ela depende da Track P-0 (fundacao de DB do Pagamento: JPA + Flyway
  + segundo banco `pagamento` no mesmo container Postgres). Hoje o Pagamento
  Service e apenas skeleton (`webmvc`, `webclient`, `security`, `kafka`,
  `actuator`), sem DB.
- O evento `AssinaturaSolicitada` carrega `{eventId, ocorridoEm, assinaturaId,
  usuarioId, plano, valor}`, no topico `assinatura-solicitada` com key =
  `assinaturaId`. A entrega e at-least-once (outbox do Assinatura), logo o
  consumer deve ser idempotente.
- O contrato do gateway mock (`docs/mock-meio-pagamento.puml`) define
  `POST /v1/payments` com header `Idempotency-Key: <assinaturaId>` e body
  `{externalReference, amount, currency, paymentMethod, notificationUrl}`. A
  resposta traz `id` (`paymentId`) e `status: "PENDING"`. O gateway ja e
  idempotente por `Idempotency-Key`: um reenvio com a mesma key devolve a mesma
  cobranca.
- Decisao D8: o Pagamento e stateful e persiste a correlacao para idempotencia e
  auditoria. Nao ha outbox no Pagamento (D2). A publicacao de
  `PagamentoStatusAtualizado` pertence ao BE-6 (Track E), fora do escopo.
- Os diagramas `docs/pagamento-cria-cobranca-sequencia.puml` e
  `docs/pagamento-cria-cobranca-processo.puml` detalham este fluxo.

## Requirements

### Must Have

- `@KafkaListener` no topico `assinatura-solicitada`, groupId `pagamento`,
  desserializando o JSON de `AssinaturaSolicitada`.
- Ao consumir, busca a correlacao por `assinaturaId`. Se ja existir cobranca
  correlacionada (mesmo `paymentId`, status `PENDING`), conclui o processamento
  sem nova chamada ao gateway (idempotencia de consumer).
- Se nao existir correlacao, chama `POST /v1/payments` no gateway mock via
  WebClient com header `Idempotency-Key: <assinaturaId>` e body
  `{externalReference: assinaturaId, amount: <valor em reais>,
  currency: "BRL", paymentMethod: "PIX", notificationUrl: <configurado>}`.
  Espera resposta com `paymentId` e `status: "PENDING"`.
- Persiste a correlacao `assinaturaId <-> paymentId` com status `PENDING`. A
  persistencia e idempotente por `assinaturaId`.
- Envia o `valor` do evento (`BigDecimal` reais) direto ao gateway mock, sem
  conversao para centavos. Ex.: `19.90`.
- Erros de consumer via `DefaultErrorHandler` com ate 3 tentativas e jitter, e
  `DeadLetterPublishingRecoverer` enviando para `assinatura-solicitada-dlq` ao
  esgotar.
- DTO `AssinaturaSolicitada` (record) batendo campo a campo com o contrato, no
  pacote `pagamento.messaging.event`.

### Should Have

- Evento invalido (campos obrigatorios ausentes ou invalidos: `assinaturaId`,
  `usuarioId`, `plano`, `valor`) e encaminhado direto para a DLQ, sem retry e sem
  chamada ao gateway.
- WebClient com timeout (connect/read) e base-url via `app.gateway.base-url`.
- Logs estruturados por `assinaturaId` para rastreabilidade (criacao de cobranca,
  correlacao ja existente, falha transitória).

### Out of Scope

- Webhook (`POST /webhooks/payments`), validacao HMAC, normalizacao de status
  (`APPROVED`/`REJECTED`) e publicacao de `PagamentoStatusAtualizado` (BE-6 /
  Track E).
- Ativacao da assinatura no Assinatura Service (BE-4).
- Fundacao de DB do Pagamento (Track P-0): pre-requisito, nao parte funcional do
  BE-5.
- Outbox no Pagamento (D2: nao ha outbox neste servico).
- Circuit breaker ou politica de retentativa sofisticada alem do
  `DefaultErrorHandler`. A DLQ de consumer basta.

## Constraints

- Pre-requisito duro: Track P-0 (JPA + Flyway + banco `pagamento`). Sem DB, nao
  ha correlacao a persistir e o BE-5 nao pode comecar.
- O schema e Flyway-owned (`ddl-auto: validate`). A tabela de correlacao exige
  migration propria.
- Entrega at-least-once: o consumer DEVE ser idempotente (busca de correlacao +
  `Idempotency-Key` no gateway).
- DTOs duplicados por servico (D7): o Pagamento cria seu proprio
  `AssinaturaSolicitada`, sem modulo compartilhado.
- Convencoes do projeto: Javadoc obrigatorio em todo publico, Google Java Style,
  CQRS Lite (command de escrita), `make verify` verde em `services/pagamento`.

## Decisoes

- **Idempotencia em duas camadas.** Resolvido (D8). Busca de correlacao por
  `assinaturaId` (camada de dominio) + `Idempotency-Key: assinaturaId` no gateway
  (camada de protocolo). Um redelivery nao duplica cobranca nem correlacao.
- **Valor em reais ponta a ponta.** Resolvido (D5 revogada). O `valor`
  (`BigDecimal` reais) e enviado direto ao gateway mock (campo `amount` em
  `float64`), sem conversao para centavos. Simplifica o consumer.
- **DLQ de consumer independente da outbox.** Resolvido (O2). O topico
  `assinatura-solicitada-dlq` via `DeadLetterPublishingRecoverer`; a outbox e
  problema do Assinatura.
- **Status persistido = `PENDING`.** Resolvido. O BE-5 apenas cria a cobranca; o
  status final chega pelo webhook no BE-6.

## Acceptance Criteria

### Criacao de cobranca bem-sucedida
- Given uma `AssinaturaSolicitada` valida no topico sem correlacao existente,
  when o consumer processa, then chama `POST /v1/payments` com
  `Idempotency-Key: <assinaturaId>`, persiste a correlacao
  `assinaturaId <-> paymentId` com status `PENDING` e confirma o offset.

### Idempotencia no redelivery
- Given a mesma `AssinaturaSolicitada` entregue novamente (at-least-once), when o
  consumer processa, then encontra a correlacao existente, NAO chama o gateway
  novamente e conclui normalmente.

### Idempotencia ponta a ponta
- Given uma criacao de cobranca que falha apos o gateway registrar o pagamento,
  mas antes de persistir a correlacao, when o evento e reprocessado, then a nova
  chamada com a mesma `Idempotency-Key` devolve o mesmo `paymentId` e a correlacao
  e persistida sem cobranca duplicada.

### Evento invalido
- Given uma `AssinaturaSolicitada` com campos obrigatorios ausentes ou
  invalidos, when consumida, then e encaminhada para `assinatura-solicitada-dlq`
  sem chamada ao gateway.

### Falha transitória do gateway
- Given o gateway retornando erro, timeout ou 5xx, when o consumer processa,
  then mantem o evento nao confirmado, retoma em ate 3 tentativas com jitter e,
  ao esgotar, envia para `assinatura-solicitada-dlq`.

### Conversao de valor
- Given um evento com `valor` `19.90` (`BASICO`), when a cobranca e criada, then
  o body enviado ao gateway leva `amount` `19.90`.

### Confinamento de escopo
- Given o BE-5 implementado, when o fluxo roda, then nenhum webhook e tratado e
  nenhuma `PagamentoStatusAtualizado` e publicada (isso e BE-6).
