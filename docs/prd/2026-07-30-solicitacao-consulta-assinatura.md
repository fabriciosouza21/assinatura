# PRD: Solicitacao e consulta de assinatura

**Date:** 2026-07-30
**Status:** Draft
**Entrega:** BE-2 do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md`
**Contrato:** `docs/openapi/assinatura.yaml`

## Problem

O BE-1 entregou o cadastro de cliente, mas o fluxo de assinatura ainda nao tem
ponto de entrada. Um cliente existe na base, porem nao consegue solicitar uma
assinatura nem acompanhar seu status. Sem a solicitacao sincrona e a consulta,
todo o downstream (pagamento, ativacao) fica bloqueado, porque nao ha assinatura
`AGUARDANDO_PAGAMENTO` para o Pagamento Service processar.

## Background

O diagrama `docs/adesao/cadastro-usuario-assinatura.puml` define dois endpoints no
inicio do fluxo: `POST /assinaturas` e `GET /assinaturas/{uuid}`. Hoje nenhum
dos dois existe, nem a tabela `assinatura`. O roadmap recortou o BE-2 como
"sem fila": solicitacao e consulta sincronas, sem Kafka e sem gateway. A
assinatura nasce em `AGUARDANDO_PAGAMENTO` e permanece assim ate o BE-4 consumir
o resultado do pagamento.

A ADR 0001 decidiu que esses endpoints sao publicos (`permitAll`) ate o
fast-follow FF-1 criar login de cliente. A decisao de IDs do roadmap se mantem:
`id` Long interno como PK e `uuid` como identidade publica exposta na API.

## Requirements

### Must Have

- Cliente solicita assinatura via `POST /assinaturas` informando `usuarioId`
  (uuid) e `plano`. Recebe `202 Accepted` com `{id: assinatura_uuid,
  status: "AGUARDANDO_PAGAMENTO"}`.
- Regra "um usuario, uma assinatura aberta": se o usuario ja possui assinatura
  em `AGUARDANDO_PAGAMENTO` ou `ATIVA`, a solicitacao retorna `409 Conflict`.
- `plano` validado contra o enum `Plano`, que mapeia cada plano ao seu valor
  mensal em reais, do tipo `BigDecimal` (BASICO 19.90, PREMIUM 39.90, FAMILIA
  59.90). Plano invalido retorna `400 Bad Request`. O mapeamento plano→valor e
  entregue no BE-2 para evitar inconsistencia com o BE-3; o `valor` nao e
  exposto nas respostas.
- `usuarioId` deve corresponder a um usuario cadastrado. Usuario inexistente
  retorna `404 Not Found`.
- `usuarioId` e `plano` sao obrigatorios e validados (nao vazios, uuid em
  formato valido).
- Consulta via `GET /assinaturas/{uuid}` devolve a assinatura completa: id,
  usuarioId, plano, dataInicio, dataExpiracao e status. Assinatura inexistente
  retorna `404 Not Found`.
- O status enviado pelo cliente no corpo (ex.: `ATIVA`) e ignorado. A assinatura
  nasce sempre `AGUARDANDO_PAGAMENTO`. `dataInicio` e `dataExpiracao` ficam
  vazias ate a ativacao (BE-4).

### Should Have

- Garantia da regra "um usuario, uma assinatura aberta" sob concorrencia via
  indice unico parcial em `(usuario_id) WHERE status IN
  ('AGUARDANDO_PAGAMENTO','ATIVA')` somado a `SELECT ... FOR UPDATE` na
  transacao.
- Tratamento de erros padronizado (400, 404, 409) reusando o
  `@RestControllerAdvice` do BE-1.

### Out of Scope

- Kafka, outbox e publicacao de `AssinaturaSolicitada` (BE-3).
- Gateway de pagamento, criacao de cobranca e ativacao da assinatura (BE-4 e
  BE-5+).
- Header `Idempotency-Key`. A protecao contra duplicidade no BE-2 e a regra
  "um usuario, uma assinatura aberta", nao idempotencia. O header reaparece no
  BE-5, igual ao `assinatura_uuid`.
- Login e autenticacao de cliente (FF-1). Endpoints publicos por ADR 0001.
- Renovacao, cancelamento, suspensao e listagem de assinaturas.

## Constraints

- O schema e Flyway-owned (`ddl-auto: validate`). A tabela `assinatura` exige
  uma nova migration `V3`.
- `id` Long interno permanece a PK; `uuid` e a identidade publica exposta.
- `usuario_id` referencia o `id` Long interno de `usuarios` (join eficiente),
  nao o uuid.
- ADR 0001: `POST /assinaturas` e `GET /assinaturas/{uuid}` sao `permitAll`.
- Convencoes do projeto: Javadoc obrigatorio em todo publico, Google Java Style,
  CQRS Lite (command de escrita, query de leitura).

## Decisoes

- **Contrato da solicitacao (input).** Resolvido. O `POST /assinaturas` recebe
  o input enxuto `{usuarioId, plano}`; id, status, dataInicio e dataExpiracao
  sao saida derivada pelo servidor, retornada apenas no
  `GET /assinaturas/{uuid}`. O status enviado no corpo e ignorado.
- **Plano→valor.** Resolvido. O enum `Plano` (BASICO 19.90, PREMIUM 39.90,
  FAMILIA 59.90, tipo `BigDecimal`) e entregue no BE-2, pronto para o evento do
  BE-3.
- **Shape de erro.** Resolvido. `400 Bad Request` retorna um `Problem`
  (RFC 7807/9457) com o array `errors` listando cada campo invalido (`campo` +
  `motivo`), para o cliente saber o que corrigir. `404 Not Found` e
  `409 Conflict` retornam corpo vazio, no mesmo molde do handler do BE-1 (409
  vazio por LGPD).

## Acceptance Criteria

### Solicitacao bem-sucedida
- Given um usuario cadastrado sem assinatura aberta, when envia
  `POST /assinaturas {usuarioId, plano}` validos sem token, then recebe
  `202 Accepted` com `{id: <assinatura_uuid>, status: "AGUARDANDO_PAGAMENTO"}`
  e a assinatura aparece na base.

### Assinatura duplicada
- Given um usuario com assinatura `AGUARDANDO_PAGAMENTO` (ou `ATIVA`), when
  envia `POST /assinaturas` para o mesmo `usuarioId`, then recebe
  `409 Conflict`.

### Status do corpo ignorado
- Given uma solicitacao com `status: "ATIVA"` no corpo, when processada, then a
  assinatura e persistida como `AGUARDANDO_PAGAMENTO` e a resposta reflete esse
  status.

### Plano invalido
- Given uma solicitacao com plano nao suportado, when enviada, then recebe
  `400 Bad Request`.

### Usuario inexistente
- Given uma solicitacao com `usuarioId` sem usuario correspondente, when
  enviada, then recebe `404 Not Found`.

### Consulta bem-sucedida
- Given uma assinatura existente, when envia `GET /assinaturas/{uuid}`, then
  recebe `200 OK` com id, usuarioId, plano, dataInicio e dataExpiracao (vazias
  enquanto aguarda) e status.

### Consulta de inexistente
- Given um uuid inexistente, when envia `GET /assinaturas/{uuid}`, then recebe
  `404 Not Found`.

### Concorrencia
- Given duas solicitacoes simultaneas para o mesmo usuario sem assinatura
  aberta, when processadas em paralelo, then exatamente uma recebe `202` e a
  outra recebe `409`.

### Publicidade (ADR 0001)
- Given a ADR 0001 vigente, when uma solicitacao ou consulta chega sem token,
  then e processada normalmente (nao `401`/`403`).
