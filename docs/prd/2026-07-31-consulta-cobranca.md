# PRD: Consulta de cobranca

**Date:** 2026-07-31
**Status:** Draft
**Entrega:** Enabler do fluxo completo de assinatura no Bruno (track de
validacao 0.2.0). Complementa BE-6 (webhook do pagamento) do roadmap
`docs/roadmap/2026-07-29-assinatura-fluxo-completo.md`.
**Contrato:** `docs/openapi/consulta-cobranca.yaml` (a criar)

## Problem

O fluxo completo de assinatura nao pode ser testado ponta a ponta. O
`POST /assinaturas` publica o evento `AssinaturaSolicitada`, o Pagamento Service
consome e cria a cobranca no gateway mock, mas o `paymentId` gerado fica
preso no servico de pagamento. O cliente de teste (colecao Bruno) consegue
ate o passo "consultar assinatura" (`AGUARDANDO_PAGAMENTO`) e nao tem como
descobrir qual `paymentId` deve aprovar no mock para disparar o webhook que
ativaria a assinatura.

Sem esse `paymentId`, o teste manual do fluxo completo, da solicitacao ate a
assinatura `ATIVA`, esta bloqueado. Quem sente a dor e o desenvolvedor e o
QA validando a release 0.2.0. Toda vez que tentam rodar o cenario fim a fim,
param no mesmo lugar.

## Background

O Pagamento Service ja persiste a cobranca em
`services/pagamento/.../cobranca/Cobranca.java`, com `assinaturaUuid` (uuid
publico da assinatura, 1:1 por indice unico) e `paymentId` (id retornado pelo
gateway). O repositorio ja oferece
`Optional<Cobranca> findByAssinaturaUuid(String)`. Ou seja, o dado existe e o
acesso existe. Falta apenas expor uma leitura.

O projeto segue CQRS Lite: commands de escrita e queries de leitura em classes
separadas, mesmo banco. O Assinatura Service ja estabeleceu o padrao com
`ConsultarAssinatura` (query `@Transactional(readOnly = true)` chamada por um
controller fino). Esta consulta de cobranca espelha esse padrao no Pagamento
Service, no agregado `Cobranca`.

A ADR 0001 (`docs/adr/0001-endpoints-assinatura-publicos-ate-client-login.md`)
decidiu que endpoints de leitura sao publicos (`permitAll`) ate o fast-forward
de login. Por analogia, a consulta de cobranca segue publica.

## Requirements

### Must Have

- Query `GET /cobrancas/{assinaturaId}` no Pagamento Service, publica
  (`permitAll`), devolvendo `200 OK` com `{paymentId, status}`. O
  `assinaturaId` da URL e o uuid publico da assinatura (mesmo valor usado como
  `Idempotency-Key` e `externalReference` no fluxo do mock).
- `paymentId` exposto como `String` (alinha com a persistencia `VARCHAR(64)` e
  com o contrato do gateway). `status` reflete o `StatusCobranca`
  (`PENDING`, `APPROVED`, `REJECTED`).
- Cobranca inexistente para o `assinaturaId` informado retorna `404 Not Found`.
- Liberacao da rota no `SecurityConfig` do pagamento, que hoje so permite
  `/actuator/health` e `/webhooks/payments`.

### Should Have

- Request na colecao Bruno `bruno/pagamento/consultar-cobranca.yml` fazendo
  `GET {{pagamentoUrl}}/cobrancas/{{assinaturaId}}` e capturando `paymentId`
  via `after-response` (`bru.setVar("paymentId", res.body.paymentId)`), para
  encadear com o `simular-aprovacao.yml` ja existente no mock gateway.
- Atualizacao do `bruno/README.md` descrevendo o fluxo completo encadeado
  (solicitar assinatura -> consultar cobranca -> simular aprovacao -> consultar
  assinatura `ATIVA`).
- Contrato OpenAPI `docs/openapi/consulta-cobranca.yaml` documentando o
  endpoint, o response e o `404`.

### Out of Scope

- Autenticacao no Pagamento Service. Nao ha filtro JWT la hoje. O endpoint e
  publico por analogia a ADR 0001. Login de cliente e fast-forward futuro.
- Listagem de cobrancas, filtros, paginacao. Apenas a consulta pontual por
  `assinaturaId`, espelho da consulta de assinatura.
- Exposicao de campos internos (`id` Long, `criadoEm`, `atualizadoEm`). O
  cliente de teste so precisa de `paymentId` e `status`.
- Renovacao. O roadmap 0.3.0 (`docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`)
  cria um agregado `PagamentoRenovacao` separado, que nao reutiliza `Cobranca`.
  Esta consulta e valida para 0.2.0 e nao cobre pagamentos de renovacao. O PRD
  registra isso para evitar expectativa futura equivocada.
- Escrita ou mutacao de cobranca por este endpoint. Leitura pura.

## Constraints

- O schema e Flyway-owned (`ddl-auto: validate`). Nenhuma migration nova:
  `findByAssinaturaUuid` ja existe e o indice unico em `assinatura_uuid` ja
  garante acesso eficiente.
- Convencoes do projeto: Javadoc obrigatorio em todo tipo e metodo publico,
  Google Java Style (2 espacos, 100 colunas), CQRS Lite (query de leitura em
  classe propria, `@Transactional(readOnly = true)`).
- Monorepo com worktrees em `.worktrees/` e GitFlow. Feature branch a partir de
  `develop`.

## Decisoes

- **Shape do response.** Resolvido. `{paymentId, status}`, espelho da decisao
  ja tomada no handoff. O cliente so precisa do `paymentId` para acionar o
  mock e do `status` para confirmar o estado. Nada mais.
- **404 sem corpo.** Resolvido. `404 Not Found` retorna corpo vazio, no mesmo
  molde do handler de assinatura (`AssinaturaExceptionHandler`), evitando
  vazar identificadores em log. Nao usa o `Erro` do webhook
  (`{"erro": "<code>"}`), porque este endpoint e uma consulta publica de
  leitura, nao um webhook com contrato de erro de gateway.
- **Scope no agregado `Cobranca`.** Resolvido. A consulta le o agregado
  `Cobranca` atual. Renovacao 0.3.0 tera agregado proprio e nao aparecera aqui.
  Isso e intencional e documentado no Out of Scope.
- **Nova exception handler.** Resolvido. Adicionar um
  `@RestControllerAdvice` dedicado a consulta (ou estender o tratamento) para
  mapear `CobrancaNaoEncontradaException` a `404` vazio. Nao misturar com o
  `WebhookExceptionHandler`, que trata contrato de gateway (401/503).

## Acceptance Criteria

### Consulta bem-sucedida
- Given uma assinatura com cobranca criada no gateway (apos o outbox publicar
  `AssinaturaSolicitada` e o Pagamento Service consumir), when envia
  `GET /cobrancas/{assinaturaId}` sem token, then recebe `200 OK` com
  `{paymentId: <id do gateway>, status: "PENDING"}`.

### Cobranca inexistente
- Given um `assinaturaId` sem cobranca associada (assinatura recem-criada antes
  do consumer processar, ou uuid que nunca existiu), when envia
  `GET /cobrancas/{assinaturaId}`, then recebe `404 Not Found` com corpo vazio.

### Endpoint publico
- Given o `SecurityConfig` do pagamento liberando `GET /cobrancas/**`, when a
  consulta chega sem cabecalho de autenticacao, then e processada normalmente
  (nao `401`/`403`).

### Status reflete o processamento
- Given uma cobranca cujo webhook de aprovacao ja foi processado, when envia
  `GET /cobrancas/{assinaturaId}`, then recebe `status: "APPROVED"`. Apos
  recusa, `status: "REJECTED"`.

### Fluxo completo no Bruno encadeia
- Given a colecao Bruno com `consultar-cobranca.yml` capturando `paymentId`,
  when o usuario executa o fluxo sugerido (cadastrar usuario -> solicitar
  assinatura -> consultar cobranca -> simular aprovacao no mock -> consultar
  assinatura), then a assinatura termina `ATIVA` sem edicao manual de variaveis
  entre passos.

### paymentId usavel no mock
- Given o `paymentId` retornado por `GET /cobrancas/{assinaturaId}`, when usado
  em `POST {{mockGatewayUrl}}/v1/mock/payments/{{paymentId}}/status` no passo
  de simulacao, then o mock dispara o webhook que leva a assinatura a `ATIVA`.
