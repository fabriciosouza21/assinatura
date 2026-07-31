# PRD: Ativação automática de assinatura por status de pagamento

**Data:** 2026-07-30
**Status:** Draft
**Track:** B / BE-4
**Serviço:** Assinatura
**Versão alvo:** `0.2.0`

## Problema

Hoje, quando um cliente solicita uma assinatura, ela fica travada em
`AGUARDANDO_PAGAMENTO` para sempre. Não existe caminho para ela sair desse estado.
O Pagamento Service já produzirá o evento `PagamentoStatusAtualizado` no Kafka
quando o gateway retornar o resultado da cobrança, mas o Assinatura Service não
escuta esse tópico. Falta a ponta que recebe a decisão de pagamento e a reflete
na assinatura.

Quem sente a dor: o cliente, que paga e nunca vê a assinatura ativa; e a operação,
que não consegue fechar o fluxo de subscrição de ponta a ponta.

## Background

A assinatura nasce `AGUARDANDO_PAGAMENTO` no momento da solicitação
(`POST /assinaturas`). A partir daí, três desfechos são possíveis conforme o
gateway de pagamento:

- O pagamento é **aprovado**. A assinatura deve ir para `ATIVA`, com data de
  início e de expiração definidas.
- O pagamento é **recusado**. A assinatura deve ir para `PAGAMENTO_RECUSADO`.
- O pagamento está **pendente**. Nada muda por enquanto; a assinatura segue
  aguardando.

O contrato travado de eventos (`docs/contratos/contrato-eventos-kafka.puml`) define o evento
`PagamentoStatusAtualizado` no tópico `pagamento-status-atualizado`, com `status`
já normalizado em `{APPROVED, REJECTED, PENDING}`. O gateway também emite
`CANCELLED` e `EXPIRED`, mas o Pagamento Service mapeia ambos para `REJECTED`
antes de publicar. Assim o consumidor só enxerga os três estados finais.

A entrega é *at-least-once*: o mock reenvia o webhook até receber ACK, e o Kafka
pode entregar mensagens duplicadas. O contrato usa `eventId` como chave de dedup
ponta a ponta (`eventId` é igual ao `X-Mock-Event-Id` do webhook). Quem consome
precisa ser idempotente por esse identificador.

Esta track fecha o lado da assinatura no fluxo de pagamento. É paralelizável com
a outbox (Track A) e independe do Pagamento Service estar pronto, pois pode ser
validada injetando um evento sintético no tópico.

## Requisitos

### Must Have
- O Assinatura Service consome o tópico `pagamento-status-atualizado` (groupId
  `assinatura`) e desserializa o evento `PagamentoStatusAtualizado` em JSON.
- `APPROVED` ativa a assinatura correspondente, passando para `ATIVA` e definindo
  data de início e de expiração.
- `REJECTED` move a assinatura para `PAGAMENTO_RECUSADO`.
- `PENDING` não altera a assinatura.
- A transição ocorre sob lock pessimista sobre a assinatura, para evitar
  condições de corrida entre entregas concorrentes.
- O consumo é idempotente por `eventId`: uma mesma notificação entregue mais de
  uma vez não aplica a transição duas vezes.
- Falhas de desserialização e de infraestrutura vão para uma DLQ
  (`pagamento-status-atualizado-dlq`) em vez de travar o consumer.

### Should Have
- O evento carrega `paymentId` (correlação com o gateway). Manter essa referência
  na assinatura ou em registro de auditoria facilita o suporte e a reconciliação
  futura.

### Out of Scope
- Produzir eventos. Esta track é só consumidora.
- Renovação automática, cancelamento e suspensão após falhas recorrentes
  (roadmap futuro).
- Reabrir uma assinatura já `ATIVA` ou `PAGAMENTO_RECUSADO`. Entregas tardias ou
  fora de ordem de um evento sobre uma assinatura já resolvida são tratadas como
  *no-op* idempotente, não como reversão.
- Exigir autenticação nos endpoints de assinatura. Esse flip é um fast-follow
  separado.

## Constraints

- O contrato do evento é travado em `docs/contratos/contrato-eventos-kafka.puml`. Não pode
  ser alterado sem bump de versão. Em particular, o evento
  `PagamentoStatusAtualizado` **não** carrega `valor`; apenas `eventId`,
  `ocorridoEm`, `assinaturaId`, `status` e `paymentId`.
- O `assinaturaId` do evento é o UUID público da assinatura (coluna `uuid`), não
  o `id` interno. A busca sob lock deve usar esse UUID.
- As datas da assinatura são `LocalDate`. A janela de validade precisa ser
  definida (ex.: início em hoje, expiração em hoje mais um mês). Esse detalhe
  afeta o que o cliente vê na consulta.
- Entrega *at-least-once*: duplicatas são esperadas e devem ser seguras. O
  `eventId` precisa ser persistido na mesma transação da transição, inserido
  apenas após o sucesso, para não registrar um processamento que não aconteceu.
- Concorrência com a Track A (outbox): ambas tocam o domínio `Assinatura` e
  adicionam migrations. O plano sugere fundir a Track A antes da B. A numeração
  da migration de dedup precisa ser coordenada para não colidir com a da outbox.
- Regras de código do repositório: Google Java Style, Javadoc em todo público,
  testes JUnit 5 com AssertJ, commits Conventional Commits em PT-BR.

## Acceptance Criteria

### Ativação por pagamento aprovado
- Given uma assinatura em `AGUARDANDO_PAGAMENTO`, when chega um
  `PagamentoStatusAtualizado` com `status = APPROVED` para o seu UUID, then a
  assinatura passa para `ATIVA` com data de início e expiração definidas, e a
  consulta `GET /assinaturas/{uuid}` reflete isso.
- Given uma assinatura em `AGUARDANDO_PAGAMENTO`, when chega `status = REJECTED`,
  then a assinatura passa para `PAGAMENTO_RECUSADO`.
- Given uma assinatura em `AGUARDANDO_PAGAMENTO`, when chega `status = PENDING`,
  then a assinatura permanece inalterada.

### Idempotência
- Given um `eventId` já processado, when o mesmo evento chega de novo
  (redelivery), then nenhuma transição é reaplicada e o resultado final é
  idêntico ao do primeiro processamento.
- Given duas entregas concorrentes do mesmo `eventId`, then apenas uma efetiva a
  transição (lock pessimista mais registro de `eventId` atômico).

### Robustez do consumer
- Given um payload inválido ou ilegível, when o consumer tenta processar, then a
  mensagem vai para a DLQ `pagamento-status-atualizado-dlq` e o consumer segue
  processando as próximas.
- Given um evento para um UUID de assinatura inexistente, when processado, then
  o consumer não quebra e não fica em loop de retry infinito (redelivery não
  muda o fato de a assinatura não existir).

### Validação isolada
- Given o tópico `pagamento-status-atualizado` disponível, when um evento
  sintético é injetado diretamente (sem o Pagamento Service), then as transições
  ocorrem e podem ser verificadas. A track não depende do Pagamento Service
  existir.
