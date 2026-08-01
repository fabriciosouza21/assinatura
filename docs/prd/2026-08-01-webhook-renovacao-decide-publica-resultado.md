# PRD: Webhook de renovação decide e publica o resultado

**Data:** 2026-08-01
**Status:** Draft
**Entrega:** BE-13 do roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md` (Stream
Pagamento do plano `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`)
**Serviço:** Pagamento
**Versão alvo:** `0.3.0`
**Contrato de referência:** `docs/contratos/contrato-eventos-renovacao.puml` (eventos
`PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas`) e
`docs/renovacao/renovacao-mecanismo-retries.md` (janela D+1/D+3/D+7 e contadores
independentes)

## Problema

O Pagamento Service já sabe registrar a cobrança de uma renovação (BE-11) e
chamar o gateway quando a tentativa vence (BE-12). Mas quando o gateway responde,
ninguém decide o que fazer com aquela resposta. O webhook atual
(`POST /webhooks/payments`) só conhece o fluxo de adesão: ele consulta o status
oficial, normaliza e publica `PagamentoStatusAtualizado`. Quando a cobrança
pertence a uma renovação, o webhook trata como se fosse adesão e publica o evento
errado, no tópico errado. O Assinatura Service nunca recebe
`PagamentoRenovacaoAprovado` nem `RenovacaoTentativasEsgotadas`, a renovação trava
em `EM_RENOVACAO` e o ciclo nunca rola para frente.

Quem sente a dor é o negócio: a cobrança foi feita no gateway, o cliente pagou
(ou recusou três vezes), mas o sistema não sabe disso. A assinatura fica presa no
limbo de `EM_RENOVACAO` sem nunca voltar para `ATIVA` nem ir para `SUSPENSA`.

BE-13 é a decisão. É quem escuta a resposta do gateway, decide se a renovação foi
aprovada, recusada (com retry) ou esgotada (três recusas), e publica o resultado
que o Assinatura Service consome para renovar ou suspender. Sem ele, o fluxo de
renovação está incompleto: sabe pedir o pagamento, mas não sabe ler a resposta.

## Background

- A assinatura hoje vale um mês e morre. A renovação automática (release 0.3.0)
  estende cada ciclo cobrando o cliente de novo, respeita quem optou por não
  renovar e suspende o acesso após três recusas seguidas.
- BE-13 fecha o Stream Pagamento da renovação. Depende de BE-11 (agregado
  `PagamentoRenovacao`/`TentativaCobranca`) e BE-12 (scheduler que chama o gateway
  e registra o `paymentId` na tentativa), ambos já em `develop`. É paralelizável
  com as tracks do Stream Assinatura (BE-9, BE-10) porque só publica eventos em
  Kafka, sem compartilhar código entre serviços.
- O webhook de adesão (release 0.2.0) já implementa o padrão que BE-13 reusa:
  valida HMAC, deduplica por `eventId` em `webhook_evento_processado`, consulta o
  status oficial no gateway (`GET /v1/payments/{paymentId}`), normaliza e publica
  em **publish-then-ACK** (`kafkaTemplate.send(...).get()` antes de persistir o
  `eventId`). BE-13 estende esse mesmo endpoint, não cria outro.
- O contrato de renovação (Gate 0) define dois eventos de saída no tópico
  `renovacao-resultado` (key = `assinaturaId`): `PagamentoRenovacaoAprovado`
  (`eventId`, `ocorridoEm`, `renovacaoId`, `assinaturaId`, `paymentId` String,
  `cicloReferencia`) e `RenovacaoTentativasEsgotadas` (`eventId`, `ocorridoEm`,
  `renovacaoId`, `assinaturaId`, `cicloReferencia`). Os tópicos
  `renovacao-resultado` e `renovacao-resultado-dlq` já estão declarados como beans
  `NewTopic` (do BE-11).
- A entrega é *at-least-once*. O webhook deduplica por `eventId` (cabeçalho
  `X-Mock-Event-Id`) ponta a ponta: um redelivery não republica o resultado.
- O mock do gateway é agnóstico a renovação (decisão D-R2). Não há mudança no
  mock; o webhook diferencia os fluxos pelo `externalReference`, que na renovação é
  o `renovacaoId`.

## Requisitos

### Must Have

- O endpoint `POST /webhooks/payments` existente despacha conforme o
  `externalReference`: se resolve para um `PagamentoRenovacao` conhecido, segue o
  fluxo de renovação; senão, segue o fluxo de ativação (adesão) atual, sem
  mudança de comportamento.
- O fluxo de renovação reusa a validação HMAC, a deduplicação de `eventId`, a
  consulta de status oficial no gateway (`GET /v1/payments/{paymentId}`) e o
  **publish-then-ACK**. O `eventId` só é persistido em
  `webhook_evento_processado` após o Kafka confirmar a publicação.
- `APPROVED`: marca a `TentativaCobranca` como `APROVADA` e publica
  `PagamentoRenovacaoAprovado`. Encerra as tentativas daquela renovação.
- `REJECTED` com tentativa menor que 3: marca a tentativa atual como `RECUSADA` e
  cria a próxima tentativa (número seguinte) com `status = PENDENTE`, sem
  `paymentId` e com `proximaTentativaEm` no futuro (backoff D+1, D+3, D+7), para o
  scheduler do BE-12 cobrar no dia certo.
- `REJECTED` com tentativa igual a 3: marca a tentativa como
  `TENTATIVAS_ESGOTADA` e publica `RenovacaoTentativasEsgotadas`. Não cria nova
  tentativa.
- `PENDING`: não consome a tentativa, não publica resultado e não cria nova
  tentativa. Aguarda um novo webhook quando o gateway mutar o status.
- Os eventos `PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas` são
  records no pacote `pagamento.messaging.event`, espelhando campo a campo o
  contrato travado, serializados em JSON (Jackson).
- A correção do scheduler do BE-12: a seleção de tentativas prontas passa a
  respeitar `proximaTentativaEm`, para que as tentativas de retry criadas após uma
  recusa só sejam cobradas na data agendada. Sem isso, o backoff de três
  tentativas não funciona.
- A invariante do `numero` da `TentativaCobranca`: a sequência passa a ser gerada
  dentro do agregado `PagamentoRenovacao` (método `registrarTentativa()`) e o
  construtor de `TentativaCobranca` rejeita `numero < 1`. BE-13 é quem introduz as
  tentativas nº 2 e nº 3 e protege o invariante.

### Should Have

- A janela de backoff (D+1, D+3, D+7) é externalizada em propriedades
  (`app.renovacao.tentativas-backoff-dias`) no bloco `app:` do `application.yaml`,
  com defaults em `.env.example`.
- Logs estruturados por `renovacaoId`, `paymentId` e `numero` da tentativa para
  rastreabilidade (decisão de aprovado, recusa com retry, recusa esgotada,
  redelivery ignorado).
- `StatusTentativa` ganha `APROVADA`, `RECUSADA` e `TENTATIVAS_ESGOTADA` (hoje só
  existe `PENDENTE`).

### Out of Scope

- Suspender a assinatura após três recusas. Decisão de negócio do Assinatura
  Service ao consumir `RenovacaoTentativasEsgotadas` (BE-10).
- Rolar o ciclo da assinatura para frente no `APPROVED`. Também é do Assinatura
  Service, ao consumir `PagamentoRenovacaoAprovado` (BE-10).
- Tocar o mock do gateway. O gateway é agnóstico a renovação (decisão D-R2); o
  webhook diferencia os fluxos pelo `externalReference`, sem mudança no mock.
- Reconciliação de cobranças `PENDING` abandonadas (webhook que nunca chega).
  Fora de escopo, igual ao 0.2.0 (decisão O-R4). Roadmap futuro.
- Guard de escala do `valor` (`valor.scale() <= 2`). O `valor` da renovação é
  validado no consumer do BE-11 na entrada; BE-13 não recebe `valor` novo, então o
  guard não se aplica aqui.
- Criar `StatusPagamentoRenovacao`. O terminal da renovação é determinado pela
  última `TentativaCobranca`; não há status separado no agregado
  `PagamentoRenovacao`.

## Constraints

- O contrato de eventos de renovação é travado (Gate 0 do plano). BE-13 publica
  `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas` em
  `renovacao-resultado` (key = `assinaturaId`) e não pode alterar os campos sem
  bump de versão.
- A deduplicação de `eventId` já existe na tabela `webhook_evento_processado`
  (coluna `event_id` com `UNIQUE`), compartilhada entre os fluxos de adesão e
  renovação. **Não há migration nova nesta track.** A próxima migration livre é
  `V6` (V5 foi consumida pelo BE-12), mas BE-13 não cria schema.
- O schema é Flyway-owned (`ddl-auto: validate`). Nenhuma coluna ou tabela é
  adicionada.
- Publish-then-ACK é mandatório: a publicação em `renovacao-resultado` bloqueia
  pelo ACK do Kafka antes de persistir o `eventId`. Se o Kafka falhar, o webhook
  retorna erro e o gateway refaz o POST (mesma deduplicação por `eventId`).
- Entrega *at-least-once*: o `eventId` (`X-Mock-Event-Id`) propaga ponta a ponta.
  Um redelivery não republica o resultado nem recria tentativa.
- Convenções do projeto: Google Java Style, Javadoc obrigatório em todo público,
  CQRS Lite (command de escrita, leitura via repository), testes JUnit 5 com
  AssertJ (`@DisplayName`, `.as(...)`), `make verify` verde em
  `services/pagamento`.

## Decisões

- **Dispatch por `externalReference`, não por rota nova.** Resolvido. O webhook
  hoje recebe `data.externalReference` no corpo. Na renovação esse campo é o
  `renovacaoId`. O dispatch resolve o `externalReference` contra
  `PagamentoRenovacaoRepository`: se existe, fluxo de renovação; senão, fluxo de
  adesão. Um endpoint, dois ramos. Mantém o contrato HMAC e o `eventId` idênticos.
- **`paymentId` resolve a tentativa.** Resolvido. O webhook traz `data.paymentId`,
  que o scheduler do BE-12 gravou na `TentativaCobranca` ao chamar o gateway.
  `TentativaCobrancaRepository.findByPaymentId(paymentId)` localiza a tentativa
  certa. Não há ambiguidade: um `paymentId` pertence a uma única tentativa.
- **Correção do scheduler fica no BE-13.** Resolvido. O scheduler do BE-12
  seleciona tentativas só por `payment_id IS NULL`, ignorando
  `proxima_tentativa_em`. Como BE-13 cria tentativas de retry marcadas para o
  futuro (D+3 após a primeira recusa), sem a correção elas seriam re-cobradas na
  próxima execução do scheduler, quebrando o backoff. A query passa a
  `status = 'PENDENTE' AND payment_id IS NULL AND (proxima_tentativa_em IS NULL OR
  proxima_tentativa_em <= now())`. BE-13 já toca o repositório para criar as
  tentativas, então a correção fica natural aqui. Sem isso, a feature de três
  tentativas não funciona de verdade.
- **Invariante `numero` fica no BE-13.** Resolvido. A revisão do BE-11 deixou em
  aberto mover a sequência para dentro do agregado. BE-13 é quem introduz as
  tentativas nº 2 e nº 3, então é o ponto natural para proteger o invariante:
  `PagamentoRenovacao.registrarTentativa()` gera o próximo número, e o construtor
  de `TentativaCobranca` rejeita `numero < 1`.
- **Sem migration nova.** Resolvido. A tabela `webhook_evento_processado` já
  deduplica por `event_id` para ambos os fluxos. O roadmap previa uma migration de
  dedup para o BE-13, mas ela já existe desde o 0.2.0. V6 fica livre para a
  próxima track.
- **Terminal de negócio só pelo caminho do webhook.** Resolvido (decisão D-R4).
  O esgotamento das três tentativas (publicação de
  `RenovacaoTentativasEsgotadas`) é decidido pelo webhook ao receber a terceira
  recusa. Falha técnica do consumer vai para a DLQ (`renovacao-resultado-dlq`,
  já declarada) e não suspende a assinatura.

## Acceptance Criteria

### Aprovação publica resultado e encerra tentativas

- Given uma `TentativaCobranca` com `paymentId` preenchido (cobrança feita pelo
  scheduler) pertencente a um `PagamentoRenovacao`, when o webhook recebe a
  mutação `APPROVED`, then marca a tentativa como `APROVADA`, publica
  `PagamentoRenovacaoAprovado` em `renovacao-resultado` (key = `assinaturaId`) e
  persiste o `eventId` só após o Kafka confirmar.

### Recusa com retry agenda a próxima tentativa

- Given uma `TentativaCobranca` número 1 (`PENDENTE`, com `paymentId`) de uma
  renovação, when o webhook recebe `REJECTED`, then marca a tentativa 1 como
  `RECUSADA` e cria a tentativa número 2 com `status = PENDENTE`, sem `paymentId`,
  com `proximaTentativaEm` em D+1. Nenhum evento é publicado em
  `renovacao-resultado`.

### Recusa esgotada publica terminal

- Given a terceira `TentativaCobranca` de uma renovação recebendo `REJECTED`,
  when o webhook processa, then marca a tentativa como `TENTATIVAS_ESGOTADA`, não
  cria nova tentativa e publica `RenovacaoTentativasEsgotadas` em
  `renovacao-resultado` (key = `assinaturaId`).

### PENDING não consome tentativa

- Given uma `TentativaCobranca` `PENDENTE` aguardando decisão, when o webhook
  consulta o gateway e o status é `PENDING`, then nenhuma tentativa muda de
  status, nenhuma nova tentativa é criada e nenhum evento é publicado. O webhook
  aguarda a próxima mutação.

### Backoff é respeitado pelo scheduler

- Given a tentativa número 2 criada pelo webhook após uma recusa, com
  `proximaTentativaEm` em D+3, when o scheduler do BE-12 roda antes de D+3, then a
  tentativa não é selecionada para cobrança. Quando o scheduler roda em D+3, then
  ela é cobrada no gateway com `Idempotency-Key = <renovacaoId>:2`.

### Redelivery de eventId não republica

- Given o mesmo `eventId` (`X-Mock-Event-Id`) entregue duas vezes pelo gateway,
  when o webhook processa a segunda vez, then a deduplicação em
  `webhook_evento_processado` impede nova decisão, nova tentativa e nova
  publicação.

### Fluxo de adesão inalterado

- Given uma cobrança de adesão (sem `PagamentoRenovacao` correspondente), when o
  webhook processa, then segue o fluxo atual: publica `PagamentoStatusAtualizado`
  em `pagamento-status-atualizado`, sem tocar agregados de renovação.

### Invariante do número protegido

- Given a criação de uma `TentativaCobranca`, when o `numero` é menor que 1, then
  o construtor lança e a tentativa não é persistida. A sequência de números é
  gerada pelo agregado `PagamentoRenovacao`, não informada à mão.
