# PRD: Recebimento de notificação de pagamento via webhook

**Data:** 2026-07-30
**Status:** Draft
**Track:** E / BE-6
**Serviço:** Pagamento
**Versão alvo:** `0.2.0`
**Contrato OpenAPI:** `docs/openapi/webhook-pagamento.yaml`

## Problema

Quando o gateway de pagamento decide uma cobrança (aprova, recusa, cancela ou
deixa expirar), o Pagamento Service nunca fica sabendo. A cobrança foi criada
no passo anterior (BE-5) e fica marcada como `PENDING` para sempre. Sem essa
decisão, o serviço não consegue avisar o Assinatura Service de que o pagamento
foi resolvido, e a assinatura do cliente trava em `AGUARDANDO_PAGAMENTO` sem
caminho de cura.

Quem sente a dor: o cliente, que paga e nunca vê a assinatura ativa; e a
operação, que não fecha o fluxo de subscrição de ponta a ponta. É a última
ponta faltante para o fluxo completo da release 0.2.0.

## Background

A cobrança nasce `PENDING` quando o Assinatura Service solicita uma assinatura
e o Pagamento Service a cria no gateway (BE-5). A partir daí, o gateway processa
o pagamento e notifica o resultado via webhook: uma chamada HTTP `POST` que ele
faz para um endpoint que o Pagamento Service precisa expor.

O contrato do mock (`docs/contratos/mock-meio-pagamento.puml`) define essa notificação. O
gateway envia um `POST /webhooks/payments` com o corpo
`{id, type, data:{paymentId, externalReference}}`, dois headers de controle
(`X-Mock-Event-Id` e `X-Mock-Signature`) e nenhuma informação de status no corpo.
O status oficial vive no próprio gateway e deve ser consultado em
`GET /v1/payments/{paymentId}`. O `externalReference` é o `assinatura_uuid` que
o Pagamento Service cadastrou ao criar a cobrança, e liga o webhook de volta à
assinatura.

A entrega da notificação é *at-least-once*. O webhook pode chegar mais de uma
vez (reenvio do gateway, redelivery de rede). Por isso o contrato de eventos
travado (`docs/contratos/contrato-eventos-kafka.puml`) usa o `eventId` do webhook como
chave de dedup ponta a ponta. O Pagamento Service precisa ser idempotente por
esse identificador.

O evento que sai dessa ponta é o `PagamentoStatusAtualizado`, no tópico
`pagamento-status-atualizado`, com `status` normalizado em `{APPROVED, REJECTED,
PENDING}`. O gateway também emite `CANCELLED` e `EXPIRED`, que devem ser
mapeados para `REJECTED` antes de publicar.

Esta track fecha o lado do Pagamento no fluxo de pagamento. É a última peça do
stream de Pagamento. Pode ser validada de forma isolada, acionando o mock e
observando a mensagem publicada, sem depender do Assinatura Service estar
consumindo.

## Requisitos

### Must Have

- O Pagamento Service expõe `POST /webhooks/payments` para receber a notificação
  do gateway.
- A notificação só é aceita se a assinatura HMAC do corpo bate com o header
  `X-Mock-Signature`. Notificação com assinatura inválida ou ausente é
  rejeitada.
- O serviço consulta o status oficial no gateway (`GET /v1/payments/{paymentId}`)
  antes de agir, em vez de confiar no corpo do webhook, que não carrega status.
- O status do gateway é normalizado: `APPROVED` e `PENDING` seguem com o mesmo
  nome; `REJECTED`, `CANCELLED` e `EXPIRED` viram `REJECTED`.
- O serviço publica `PagamentoStatusAtualizado` no tópico
  `pagamento-status-atualizado`, com key = `assinaturaId`, status normalizado e
  `eventId` igual ao `X-Mock-Event-Id` do webhook.
- O ACK ao gateway segue *publish-then-ACK*: só retorna `200` depois que o Kafka
  confirmar a publicação. Se a publicação falhar, retorna não-`200`, sinalizando
  ao gateway que deve reenviar.
- O processamento é idempotente por `eventId`: a mesma notificação entregue mais
  de uma vez publica o evento uma única vez. O `eventId` só é registrado como
  processado depois de uma publicação bem-sucedida, nunca antes, para não perder
  o evento caso o serviço caia entre registrar e publicar.

### Should Have

- A cobrança existente tem seu status atualizado para refletir a decisão do
  gateway (auditoria e inspeção operacional).
- Falhas transitórias na consulta ao gateway ou na publicação no Kafka resultam
  em não-`200`, permitindo o reenvio, sem descartar a notificação.

### Out of Scope

- *Retry* automático no lado do Pagamento Service. A estratégia de confiabilidade
  é o reenvio do gateway (publish-then-ACK) combinado com a dedup. Não há
  outbox nem agendador nesta ponta, por decisão de arquitetura.
- Mudança no contrato do mock. O mock já implementa o webhook e a consulta de
  status. O lado do Pagamento se adapta a ele.
- Autenticação por JWT ou Spring Security no endpoint do webhook. A autenticação
  do webhook é a assinatura HMAC, não uma credencial de usuário.
- Reconciliação de cobranças abandonadas (varredura periódica de `PENDING`
  antigos). Fica para roadmap futuro.

## Constraints

- O contrato de eventos está travado em `docs/contratos/contrato-eventos-kafka.puml`. Não
  pode mudar sem bump de versão.
- A chave HMAC já existe configurada (`app.gateway.webhook-secret`), mas não está
  em uso. Esta entrega a conecta à validação.
- O endpoint deve ser público (`permitAll`) na configuração de segurança, pois a
  autenticação é o HMAC, não o Spring Security.
- Seguir o padrão de dedup já usado no Assinatura Service (tabela de eventos
  processados com índice único em `eventId`), espelhando a solução do consumer
  do outro serviço.

## Acceptance Criteria

### Recebimento e autenticidade

- Given uma notificação com assinatura HMAC válida, when o gateway chama
  `POST /webhooks/payments`, then o serviço a processa e retorna `200`.
- Given uma notificação sem o header `X-Mock-Signature`, when o gateway chama o
  endpoint, then o serviço rejeita sem processar e retorna não-`200`.
- Given uma notificação com assinatura que não bate com o corpo, when o gateway
  chama o endpoint, then o serviço rejeita e retorna não-`200`.

### Consulta e normalização de status

- Given uma notificação para um `paymentId` aprovado no gateway, when o serviço
  processa, then consulta o gateway, recebe `APPROVED` e publica
  `PagamentoStatusAtualizado` com `status = APPROVED`.
- Given uma notificação cujo `paymentId` está `REJECTED`, `CANCELLED` ou
  `EXPIRED` no gateway, when o serviço processa, then publica com
  `status = REJECTED`.
- Given uma notificação cujo `paymentId` está `PENDING`, when o serviço
  processa, then publica com `status = PENDING` e retorna `200`.

### Publicação confiável (publish-then-ACK)

- Given uma notificação válida e Kafka disponível, when o serviço publica
  `PagamentoStatusAtualizado`, then só retorna `200` após o Kafka confirmar.
- Given uma notificação válida e Kafka indisponível, when a publicação falha,
  then o serviço retorna não-`200` e o evento é perdido do ponto de vista do
  Kafka, permitindo o reenvio do gateway recuperar.

### Idempotência

- Given uma notificação com `eventId` já processado, when o gateway reenvia a
  mesma notificação, then o serviço não republica o evento e retorna `200`.
- Given uma notificação com `eventId` ainda não registrado, when o serviço cai
  entre publicar no Kafka e registrar o `eventId`, then um reenvio do gateway
  republica o evento (o `eventId` só é gravado após o publish confirmado).

### Payload do evento

- Given uma notificação processada com sucesso, when o evento é publicado,
  then ele carrega `eventId`, `ocorridoEm`, `assinaturaId`, `status`
  (normalizado) e `paymentId`, conforme o contrato travado.
