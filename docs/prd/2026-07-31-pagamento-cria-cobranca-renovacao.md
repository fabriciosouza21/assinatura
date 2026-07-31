# PRD: Cria a cobrança da renovação

**Data:** 2026-07-31
**Status:** Draft
**Entrega:** BE-11 do roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md` (fundaçao do Stream Pagamento do plano `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`)
**Serviço:** Pagamento
**Versão alvo:** `0.3.0`
**Contrato de referência:** `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 78-87) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 96-121)

## Problema

Hoje o Pagamento Service só sabe cobrar a adesão de uma assinatura: a primeira
cobrança, via `POST /v1/payments`, com `Cobranca` em 1:1 com a assinatura. Quando
o ciclo vence e o Assinatura Service pede a renovação publicando
`RenovacaoSolicitada`, ninguém do lado do Pagamento escuta esse pedido. Não
existe estrutura para registrar a cobrança de uma renovação nem para orquestrar
as tentativas posteriores. O pedido cai no vazio, o cliente não é cobrado de novo
e a receita recorrente não acontece.

Quem sente a dor: o negócio, que perde a renovação de cada ciclo; e a operação,
que não consegue fechar o fluxo de assinatura recorrente de ponta a ponta.

BE-11 é o primeiro passo stateful da renovação no Pagamento. Receber o pedido,
registrar a renovação como um processo de cobrança próprio (com idempotência por
renovação e uma primeira tentativa pendente) e abrir caminho para quem vai de
fato chamar o gateway (BE-12) e decidir o resultado pelo webhook (BE-13).

## Background

- A assinatura hoje vale um mês e morre. A renovação automática (release 0.3.0)
  estende cada ciclo cobrando o cliente de novo, respeita quem optou por não
  renovar e suspende o acesso após três recusas seguidas.
- BE-11 é a fundação do Stream Pagamento. Depende só de travar o contrato de
  renovação (Gate 0). É paralelizável com as tracks do Stream Assinatura
  (BE-7, BE-8, BE-9, BE-10) porque toca serviço e agregados distintos, sem
  compartilhar código entre os serviços.
- O contrato travado de `RenovacaoSolicitada` (Assinatura produz via outbox,
  Pagamento consome) carrega `{eventId UUID, ocorridoEm Instant, renovacaoId UUID,
  assinaturaId UUID, plano Plano, valor BigDecimal, cicloReferencia int}` no
  tópico `renovacao-solicitada`, com `key = assinaturaId` e `groupId = pagamento`.
- O Pagamento Service já tem o esqueleto de consumer reutilizável: o consumer de
  `AssinaturaSolicitada` (BE-5) faz idempotência por `assinaturaUuid` e o
  `DefaultErrorHandler` existente manda falhas para a DLQ por sufixo de tópico.
  BE-11 reusa essa infra, mas com idempotência por `renovacaoId` e agregado novo.
- A entrega é *at-least-once*: o Kafka pode entregar `RenovacaoSolicitada` mais de
  uma vez. O consumer precisa ser idempotente por `renovacaoId`.

## Requisitos

### Must Have

- `@KafkaListener` no tópico `renovacao-solicitada`, groupId `pagamento`,
  desserializando o JSON de `RenovacaoSolicitada` em record no pacote
  `pagamento.messaging.event`, espelhando campo a campo o contrato travado.
- Ao consumir, cria o pagamento da renovação e a tentativa número 1 com
  `status = PENDENTE`. O pagamento da renovação é um agregado novo
  (`PagamentoRenovacao` + `TentativaCobranca`), não reutiliza `Cobranca`.
- A criação é idempotente por `renovacaoId`: um `INSERT` sob
  `ON CONFLICT (renovacao_id) DO NOTHING`. Se a renovação já existia, o consumer
  conclui o processamento sem recriar pagamento nem tentativa.
- A tentativa número 1 nasce `PENDENTE`, sem `paymentId` e sem
  `proximaTentativaEm` (aguardando o scheduler do BE-12 chamá-la no gateway).
- Os tópicos produtores `renovacao-resultado` e `renovacao-resultado-dlq` ficam
  declarados como beans `NewTopic`, prontos para o BE-13 publicar o resultado.
- Falhas de consumer vão para `renovacao-solicitada-dlq` via o
  `DefaultErrorHandler` existente (herdado, sem configuração extra).
- Migration `V4` criando as tabelas `pagamento_renovacao` e `tentativa_cobranca`,
  com `UNIQUE (renovacao_id)` em `pagamento_renovacao` garantindo a idempotência
  no nível do banco.

### Should Have

- Evento inválido (campos obrigatórios ausentes ou inválidos: `eventId`,
  `renovacaoId`, `assinaturaId`, `valor`, `cicloReferencia`) é encaminhado direto
  para a DLQ, sem tentar persistir.
- Logs estruturados por `renovacaoId` e `assinaturaId` para rastreabilidade
  (criação de pagamento de renovação, renovação já existente, falha transitória).

### Out of Scope

- Chamar o gateway (`POST /v1/payments`). Isso é BE-12, que agenda a tentativa
  vencida e cobra no gateway com `Idempotency-Key = renovacaoId:tentativa`.
- Decidir o resultado da renovação (`APPROVED`/`REJECTED`/esgotado) e publicar
  `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas` em
  `renovacao-resultado`. Isso é BE-13, pelo webhook do gateway.
- Suspender a assinatura após três recusas. Decisão de negócio do Assinatura
  Service ao receber `RenovacaoTentativasEsgotadas` (BE-10).
- Dedup de `eventId` de webhook de renovação (migration `V5`). É do BE-13, que
  estende a `webhook_evento_processado` existente.
- Tocar o mock do gateway. O gateway é agnóstico a renovação (decisão D-R2); o
  pagamento de renovação reusa `POST /v1/payments` via cliente WebClient, sem
  mudança no mock.
- Outbox no Pagamento. Continua sem outbox neste serviço (decisão D2 do 0.2.0).

## Constraints

- O contrato do evento é travado. Não pode ser alterado sem bump de versão. Em
  particular, `RenovacaoSolicitada` carrega `valor` em `BigDecimal` (reais),
  herança da decisão D5 do 0.2.0, sem conversão para centavos.
- O schema é Flyway-owned (`ddl-auto: validate`). As tabelas novas exigem
  migration própria, numerada como `V4` (o Pagamento termina em `V3` hoje).
- Entrega *at-least-once*: duplicatas são esperadas e devem ser seguras. A
  idempotência por `renovacaoId` precisa ser garantida no banco, via `UNIQUE` e
  `ON CONFLICT DO NOTHING`, na mesma transação da criação do pagamento e da
  tentativa.
- `PagamentoRenovacao` é agregado novo porque `Cobranca` tem `uq_cobranca_assinatura`
  (1:1 assinatura) e a renovação tem várias cobranças por assinatura ao longo do
  tempo, com idempotência por `renovacaoId`, não por `assinaturaUuid` (decisão D-R6).
- DTOs duplicados por serviço (decisão D7): o Pagamento cria seu próprio
  `RenovacaoSolicitada`, sem módulo compartilhado com o Assinatura Service.
- Convenções do projeto: Google Java Style, Javadoc obrigatório em todo público,
  CQRS Lite (command de escrita), testes JUnit 5 com AssertJ, `make verify` verde
  em `services/pagamento`.

## Decisões

- **Idempotência por `renovacaoId` no banco.** Resolvido (D-R6). Ao contrário do
  BE-5, que usa check-then-act por `assinaturaUuid`, BE-11 usa
  `ON CONFLICT (renovacao_id) DO NOTHING`: idempotência declarativa na inserção.
  Um redelivery não duplica pagamento nem tentativa.
- **Agregado novo, não reuso de `Cobranca`.** Resolvido (D-R6). `Cobranca` é 1:1
  com assinatura. A renovação cria `PagamentoRenovacao` (1:N com tentativas ao
  longo dos ciclos) e `TentativaCobranca` (uma linha por tentativa por renovação).
- **Tentativa nº 1 nasce `PENDENTE` e sem chamada ao gateway.** Resolvido. BE-11
  só registra que existe uma renovação a cobrar. O scheduler do BE-12 é quem
  efetivamente chama o gateway quando a tentativa está vencida. Isso separa
  claramente a fundação (registrar) da ação (cobrar) e da decisão (decidir pelo
  webhook).
- **DLQ de consumer independente do resultado de negócio.** Resolvido (D-R4).
  `renovacao-solicitada-dlq` trata falha técnica do consumer (payload inválido,
  erro de banco). Uma recusa de pagamento (resultado de negócio) não vai para a
  DLQ; é tratada pelo caminho do webhook (BE-13).

## Acceptance Criteria

### Criação do pagamento de renovação

- Given uma `RenovacaoSolicitada` válida no tópico sem pagamento existente para
  aquele `renovacaoId`, when o consumer processa, then cria o
  `PagamentoRenovacao` e a `TentativaCobranca` número 1 com `status = PENDENTE`,
  sem `paymentId`, e confirma o offset.

### Idempotência no redelivery

- Given a mesma `RenovacaoSolicitada` entregue novamente (at-least-once), when o
  consumer processa, then o `ON CONFLICT (renovacao_id) DO NOTHING` impede a
  duplicação, nenhuma nova tentativa é criada e o processamento conclui
  normalmente.

### Idempotência ponta a ponta

- Given uma criação que falha após o banco registrar o pagamento, mas antes de
  confirmar o offset, when o evento é reprocessado, then o conflito de
  `renovacaoId` é detectado e o consumer conclui sem duplicar.

### Evento inválido

- Given uma `RenovacaoSolicitada` com campos obrigatórios ausentes ou inválidos,
  when consumida, then é encaminhada para `renovacao-solicitada-dlq` sem tentar
  persistir.

### Falha transitória de infraestrutura

- Given um erro de banco ou de infraestrutura durante o processamento, when o
  consumer processa, then mantém o evento não confirmado, retoma em até três
  tentativas com jitter e, ao esgotar, envia para `renovacao-solicitada-dlq`.

### Confinamento de escopo

- Given o BE-11 implementado, when uma `RenovacaoSolicitada` é consumida, then
  nenhum chamado ao gateway é feito (isso é BE-12) e nenhum evento é publicado em
  `renovacao-resultado` (isso é BE-13).
- Given os tópicos produtores, when o BE-11 é implementado, then `renovacao-resultado`
  e `renovacao-resultado-dlq` estão declarados como beans `NewTopic`, prontos para
  o BE-13 publicar.
