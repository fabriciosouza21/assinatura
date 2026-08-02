# PRD: Consumer de resultado da renovação

**Data:** 2026-08-01
**Status:** Draft
**Track:** B / BE-10
**Serviço:** Assinatura
**Versão alvo:** `0.3.0`

## Problema

Desde o BE-9, assinaturas com renovação automática vão para `EM_RENOVACAO`
no dia do vencimento e disparam um pedido de renovação ao Pagamento. A partir
desse momento a assinatura fica paralisada. O Assinatura Service publica
`RenovacaoSolicitada`, mas não escuta o desfecho. Nada tira a assinatura de
`EM_RENOVACAO`.

O cliente que pagou a renovação com sucesso permanece com a assinatura congelada
em vez de ver o novo ciclo ativo. O cliente que teve a cobrança recusada três
vezes segue com acesso em aberto em vez de ser suspenso. A operação não fecha o
ciclo de renovação de ponta a ponta.

## Background

A renovação automática tem dois lados. O scheduler do BE-9 (`RenovacaoScheduler`)
seleciona assinaturas vencidas, cria o agregado `Renovacao` em `PENDENTE`, move a
assinatura para `EM_RENOVACAO` e publica `RenovacaoSolicitada` na outbox. O
Pagamento Service consume esse pedido, executa até três tentativas de cobrança e
publica o desfecho no tópico `renovacao-resultado` (publish-then-ACK). Dois
eventos chegam nesse tópico, discriminados pelo campo `tipo`:

- **`PagamentoRenovacaoAprovado`** (`tipo = APROVADO`). A cobrança foi aprovada.
  A assinatura deve estender o ciclo e voltar para `ATIVA`.
- **`RenovacaoTentativasEsgotadas`** (`tipo = ESGOTADO`). A terceira recusa
  consecutiva encerrou as tentativas. A assinatura deve ser suspensa.

A entrega é *at-least-once*: o gateway reenvia o webhook até receber ACK e o
Kafka pode entregar duplicatas. O contrato usa `eventId` como chave de dedup
ponta a ponta (`eventId` é igual ao `X-Mock-Event-Id` do webhook). Quem consome
precisa ser idempotente por esse identificador.

Este consumer fecha o lado da Assinatura no fluxo de renovação. É paralelizável
com as tracks do Pagamento (BE-11, BE-12, BE-13) e pode ser validado de forma
isolada injetando um evento sintético no tópico, sem depender do Pagamento
Service existir.

## Requisitos

### Must Have
- O Assinatura Service consome o tópico `renovacao-resultado` (groupId
  `assinatura`) e desserializa o JSON em `PagamentoRenovacaoAprovado` ou
  `RenovacaoTentativasEsgotadas`, discriminados pelo campo `tipo`
  (`APROVADO`/`ESGOTADO`).
- `APROVADO` aprova a renovação (`Renovacao.aprovar()`), estende o ciclo da
  assinatura (`Assinatura.renovar(novoFimCiclo)`) e volta a assinatura para
  `ATIVA`. Publica `AssinaturaRenovada` na outbox na mesma transação.
- `ESGOTADO` esgota as tentativas da renovação
  (`Renovacao.esgotarTentativas()`) e suspende a assinatura
  (`Assinatura.suspender()`), levando para `SUSPENSA`. Publica
  `AssinaturaSuspensa` na outbox na mesma transação.
- A transição ocorre sob lock pessimista sobre a `Renovacao`, para evitar
  condições de corrida entre entregas concorrentes.
- O consumo é idempotente por `eventId`: uma mesma notificação entregue mais de
  uma vez não aplica a transição duas vezes. A tabela
  `renovacao_evento_processado` (migration V9) registra cada `eventId`
  processado.
- Falhas de desserialização e de validação vão para a DLQ
  `renovacao-resultado-dlq` em vez de travar o consumer.
- Os eventos de saída `AssinaturaRenovada` e `AssinaturaSuspensa` são definidos
  como records, com tópicos de destino mapeados em `app.kafka.rotas-evento-topico`
  (`assinatura-renovada`, `assinatura-suspensa`).

### Should Have
- Definir e declarar os tópicos `assinatura-renovada` e `assinatura-suspensa`
  como `NewTopic` no Assinatura, para que existam no broker mesmo sem consumer
  imediato. Os eventos ficam disponíveis para analytics e operação.

### Out of Scope
- Renovação manual, reativação de assinatura suspensa ou cancelamento após
  suspensão (roadmap futuro).
- Reabrir uma renovação já resolvida. Entregas tardias ou fora de ordem de um
  evento sobre uma renovação já em `APROVADA` ou `TENTATIVAS_ESGOTADA` são
  tratadas como *no-op* idempotente, não como reversão.
- Consumir `RenovacaoSolicitada`. Esse é o lado Pagamento (BE-11).
- Produzir `PagamentoRenovacaoAprovado` ou `RenovacaoTentativasEsgotadas`. Esse
  é o BE-13 (Pagamento).
- Validar o fluxo ponta a ponta com o Pagamento Service. A validação é isolada
  por injeção de evento sintético.

## Constraints

- O contrato dos eventos de entrada está travado em
  `docs/contratos/contrato-eventos-renovacao.puml`. O campo discriminador `tipo`
  (`APROVADO`/`ESGOTADO`) é um adendo a esse contrato, alinhado com o BE-13 (que
  ainda não existe). A definição precisa ser refletida no `.puml` ao implementar.
- Os campos comuns aos dois eventos são `eventId UUID`, `ocorridoEm Instant`,
  `renovacaoId UUID`, `assinaturaId UUID`, `cicloReferencia int`. Apenas
  `APROVADO` carrega `paymentId String`.
- `renovacaoId` e `assinaturaId` são os UUIDs públicos (coluna `uuid`), não os
  `id` internos. A busca sob lock deve usar esses UUIDs.
- A data do novo fim de ciclo é `LocalDate`. A regra de extensão (ex.: novo
  `fimCiclo` igual ao `fimCiclo` anterior mais um mês) precisa ser definida e
  afeta o que o cliente vê na consulta.
- Entrega *at-least-once*: duplicatas são esperadas e devem ser seguras. O
  `eventId` precisa ser persistido na mesma transação da transição, inserido
  apenas após o sucesso, para não registrar um processamento que não aconteceu.
- `Assinatura.renovar(LocalDate)` não valida o status atual. O caller (este
  consumer) é responsável por garantir que a assinatura está em `EM_RENOVACAO`.
  O lock pessimista sobre a renovação mais o fluxo (o BE-9 é o único que coloca
  em `EM_RENOVACAO`, este consumer é o único que tira dali) bastam.
- `Assinatura.suspender()` lança `IllegalStateException` se a assinatura estiver
  `ATIVA`. O caminho de esgotamento recebe sempre uma assinatura em
  `EM_RENOVACAO` (posta pelo BE-9), então `suspender()` não lança nesse fluxo.
- Migration V9 (reservada pelo PR #18 ao BE-10) cria
  `renovacao_evento_processado`. Precedente: `V6__cria_tabela_pagamento_evento_processado.sql`.
  Sem colisão com V8 (BE-9).
- Regras de código do repositório: Google Java Style, Javadoc em todo público,
  testes JUnit 5 com `@DisplayName` e AssertJ, commits Conventional Commits em
  PT-BR. Injeção de `Clock` e uso de `LocalDate.now(clock)` /
  `Instant.now(clock)` são obrigatórios (lição do review do BE-9).

## Acceptance Criteria

### Renovação aprovada
- Given uma assinatura `ATIVA` com `proximaRenovacaoEm` no passado (postada em
  `EM_RENOVACAO` pelo BE-9) e uma `Renovacao` em `PENDENTE`, when chega um
  `PagamentoRenovacaoAprovado` (`tipo = APROVADO`) com o seu `renovacaoId`, then
  a assinatura volta para `ATIVA` com novo ciclo definido, a renovação vai para
  `APROVADA` e um evento `AssinaturaRenovada` fica `PENDENTE` na outbox.
- Given a renovação do passo anterior resolvida, when a consulta
  `GET /assinaturas/{uuid}` é feita, then reflete o novo ciclo (`inicioCiclo`,
  `fimCiclo`, `proximaRenovacaoEm` atualizados).

### Tentativas esgotadas
- Given uma assinatura em `EM_RENOVACAO` e uma `Renovacao` em `PENDENTE`, when
  chega um `RenovacaoTentativasEsgotadas` (`tipo = ESGOTADO`) com o seu
  `renovacaoId`, then a assinatura vai para `SUSPENSA`, a renovação vai para
  `TENTATIVAS_ESGOTADA` e um evento `AssinaturaSuspensa` fica `PENDENTE` na
  outbox.

### Idempotência
- Given um `eventId` já processado, when o mesmo evento chega de novo
  (redelivery), then nenhuma transição é reaplicada, nenhuma outbox é gravada de
  novo e o resultado final é idêntico ao do primeiro processamento.
- Given duas entregas concorrentes do mesmo `eventId`, then apenas uma efetiva a
  transição (lock pessimista sobre a renovação mais inserção atômica do
  `eventId`).

### Robustez do consumer
- Given um payload inválido, ilegível ou sem o campo `tipo`, when o consumer
  tenta processar, then a mensagem vai para a DLQ `renovacao-resultado-dlq` e o
  consumer segue processando as próximas.
- Given um `tipo` desconhecido (nem `APROVADO` nem `ESGOTADO`), when o consumer
  processa, then a mensagem vai para a DLQ sem aplicar nenhuma transição.
- Given um evento para um `renovacaoId` inexistente, when processado, then o
  consumer não quebra e não fica em loop de retry infinito (redelivery não muda
  o fato de a renovação não existir).

### Validação isolada
- Given o tópico `renovacao-resultado` disponível, when um evento sintético
  `APROVADO` é injetado diretamente (sem o Pagamento Service), then a transição
  de renovação ocorre e pode ser verificada. O mesmo vale para `ESGOTADO`. O
  consumer não depende do Pagamento Service existir.
