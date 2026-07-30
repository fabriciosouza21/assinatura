# Roadmap: Fluxo completo de assinatura

**PRD:** `docs/cadastro-usuario-assinatura.puml` (contrato de referência)
**Versão alvo:** `0.2.0`
**Branch base:** `develop` (criada a partir de `feat/setup-docker-mock-pagamento`)

## Decisões de produto

- **IDs**: manter `id` Long interno (eficiente para joins e buscas internas) e
  adicionar coluna `uuid` exposta em todos os payloads da API. Alinhado ao
  contrato do diagrama, sem custo de reescrever a tabela `users` existente.
- **Escopo**: apenas o fluxo do diagrama `cadastro-usuario-assinatura.puml`.
  Renovação automática, cancelamento e suspensão após falhas ficam de fora
  (roadmap futuro).
- **Valor do plano**: enum simples no Assinatura Service mapeando plano → valor
  (ex.: PREMIUM → 39.90). O evento `AssinaturaSolicitada` carrega o valor.
- **Entregas incrementais**: cada MR é testável de forma isolada, sem depender
  do próximo para validar.

---

## Backend — Assinatura Service

### BE-1 — Cadastro de usuário
`feat/cadastra-usuario`
- Cliente cadastra um usuário via `POST /usuarios` e recebe um `usuario_uuid`.
- Coluna `uuid` adicionada à tabela `users`; `nome` e `email` passam a fazer
  parte do cadastro. Seed `admin` preservado.

### BE-2 — Solicitação e consulta de assinatura (sem fila)
`feat/solicita-assinatura-sem-fila`
- Cliente solicita assinatura via `POST /assinaturas` e recebe `202` com status
  `AGUARDANDO_PAGAMENTO`. Requisição duplicada para o mesmo usuário retorna
  `409`.
- Consulta via `GET /assinaturas/{uuid}` devolve a assinatura com plano, datas
  e status.
- Garantia "um usuário, uma assinatura aberta" via índice único parcial em
  `(usuario_id) WHERE status IN ('AGUARDANDO_PAGAMENTO','ATIVA')` e
  `SELECT ... FOR UPDATE` na transação.

### BE-3 — Outbox e publicação de AssinaturaSolicitada
`feat/outbox-assinatura-solicitada`
- A solicitação de assinatura grava um evento `AssinaturaSolicitada` na tabela
  `outbox` na mesma transação do insert da assinatura.
- Um publisher lê a outbox e publica o evento no Kafka, garantindo entrega sem
  perder o pedido do cliente.

### BE-4 — Consumo de PagamentoStatusAtualizado
`feat/consome-pagamento-status`
- Assinatura Service consome `PagamentoStatusAtualizado` do Kafka.
- Status `APPROVED` ativa a assinatura (define datas); `REJECTED` marca como
  `PAGAMENTO_RECUSADO`; `PENDING` não altera. Tudo sob lock pessimista.
- Testável com evento sintético injetado no tópico, antes do pagamento existir.

### FF-1 — Login de cliente (fast-follow)
- Fecha a lacuna aberta pelo ADR 0001 (`docs/adr/0001-endpoints-assinatura-publicos-ate-client-login.md`):
  o cadastro passa a criar também um `User` auth (senha) ligado ao `Usuario` de
  domínio, habilitando `/auth/login` para clientes.
- Pré-requisito para exigir autenticação nos endpoints de assinatura. Deve cair
  antes do fluxo end-to-end acionar o gateway com transações reais (BE-5 em
  diante).

---

## Backend — Pagamento Service

### BE-5 — Consumo de AssinaturaSolicitada e chamada ao gateway
`feat/pagamento-cria-cobranca`
- Pagamento Service consome `AssinaturaSolicitada` e cria a cobrança no gateway
  mock via `POST /v1/payments`, usando `Idempotency-Key` igual ao
  `assinatura_uuid`.
- Cliente HTTP (`WebClient`) configurado com `app.gateway.base-url`. Resposta
  `PENDING` é persistida para correlação posterior.

### BE-6 — Webhook de pagamento
`feat/webhook-pagamento`
- Gateway notifica o Pagamento Service via `POST /webhooks/payments`.
- Valida assinatura HMAC (header `X-Mock-Signature`), deduplica `eventId`,
  consulta o status oficial no gateway e publica `PagamentoStatusAtualizado`
  no Kafka com `assinaturaId` e status final.

---

## Documentação

### DOCS-1 — Changelog e versão
`release/0.2.0`
- Atualizar versão para `0.2.0` em ambos os `pom.xml`.
- Atualizar `CHANGELOG.md` com o fluxo completo de assinatura.

---

## Ordem dos Entregaveis

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 0 | Criar `develop` a partir de `feat/setup-docker-mock-pagamento` | — | [x] |
| 1 | Cadastro de usuário (`POST /usuarios` com `uuid`) | 0 | [x] |
| 2 | Solicitação + consulta de assinatura sem fila (`POST`/`GET`, `409`) | 1 | [x] |
| FF-1 | Login de cliente (fecha lacuna `User`↔`Usuario` do ADR 0001) | 1, 2 | [ ] |
| 3 | Outbox + publicação de `AssinaturaSolicitada` no Kafka | 2 | [ ] |
| 4 | Consumer de `PagamentoStatusAtualizado` (ativa/recusa) | 2 | [ ] |
| 5 | Pagamento: consumer `AssinaturaSolicitada` + chamada ao gateway | 3 | [ ] |
| 6 | Webhook de pagamento (HMAC + dedup + publica resultado) | 5 | [ ] |
| 7 | Changelog + bump versão `0.2.0` | 1-6 | [ ] |

**Fluxo de validação end-to-end (após BE-6):** cadastra usuário → solicita
assinatura → outbox publica → pagamento cria cobrança no mock → simula
`APPROVED` no mock → webhook dispara → assinatura ativa → consulta retorna
`ATIVA`.
