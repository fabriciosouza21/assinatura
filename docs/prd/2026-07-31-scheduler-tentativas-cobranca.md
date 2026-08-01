# PRD: Repete a cobranca apos recusa

**Data:** 2026-07-31
**Status:** Draft
**Entrega:** BE-12 do roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md` (Stream
Pagamento do plano `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`)
**Servico:** Pagamento
**Versao alvo:** `0.3.0`
**Contrato de referencia:** `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas
89-163) e `docs/renovacao/renovacao-mecanismo-retries.md` (linhas 81-104)

## Problema

O Pagamento Service hoje sabe registrar uma cobranca de renovacao, mas nao sabe
cobra-la. A track BE-11 (PR #10) criou o agregado `PagamentoRenovacao` com a
primeira `TentativaCobranca` em `PENDENTE`, sem `paymentId` e sem
`proximaTentativaEm`. Essa tentativa fica parada no banco esperando alguem que
nunca chega: nao existe quem dispare o `POST /v1/payments` no gateway. O cliente
nao e cobrado de novo e a receita recorrente segue sem acontecer.

Quem sente a dor e o negocio, que perde a renovacao de cada ciclo por falta do
passo que efetivamente pede o pagamento ao gateway. Sem o BE-12, o pedido de
renovacao cai numa fila morta do lado do Pagamento: registrado, mas nunca
executado.

BE-12 e o braco que age. Encontra tentativas de cobranca prontas para executar,
chama o gateway com a `Idempotency-Key` certa e registra o `paymentId` retornado.
Apos o aceite (`PENDING`), ele para: a decisao de aprovado ou recusado nao e
dele, vem pelo webhook (BE-13). A separacao e deliberada, e a regra central do
design e que falha tecnica do gateway nao consome nenhuma das tres tentativas de
negociocio (decisao D-R4).

## Background

- A assinatura hoje vale um mes e morre. A renovacao automatica (release 0.3.0)
  estende cada ciclo cobrando o cliente de novo, respeita quem optou por nao
  renovar e suspende o acesso apos tres recusas seguidas.
- BE-12 depende de BE-11 (fundaçao do Stream Pagamento, ja em `develop`). E
  paralelizavel com as tracks do Stream Assinatura (BE-9, BE-10) e com BE-13,
  porque toca agregados do Pagamento sem compartilhar codigo entre servicos.
- BE-11 criou a primeira tentativa em `PENDENTE` com `proximaTentativaEm = null`.
  Isso e proposital: o registro da cobranca e separado da açao de cobrar. BE-12
  e quem cobra essa primeira tentativa, e tambem quem cobrara as subsequentes
  (`numero = 2`, `numero = 3`) que o webhook do BE-13 agendar com
  `proximaTentativaEm` no futuro apos uma recusa.
- O `GatewayPagamentoClient` atual so tem `criarCobranca(assinaturaId, valor)`,
  com `Idempotency-Key = assinaturaId` e `externalReference = assinaturaId`. Esse
  contrato e do fluxo de adesao (1:1 cobranca-assinatura). A renovacao precisa de
  `Idempotency-Key = renovacaoId:numero` e `externalReference = renovacaoId`
  (decisao D-R2), porque varias tentativas da mesma renovaçao coexistem e o
  gateway deduplica pela chave.
- O Pagamento Service nao tem `@EnableScheduling` nem nenhum `@Scheduled` hoje.
  O unico scheduler do monorepo e o `OutboxPublisher` do Assinatura (BE-3). BE-12
  introduz o primeiro scheduler do Pagamento.

## Requisitos

### Must Have

- `@Scheduled` que encontra tentativas de cobranca prontas para executar e chama
  o gateway. O agendamento roda periodicamente (intervalo configuravel) e o
  criterio de "pronta" cobre dois casos: tentativa `PENDENTE` sem `paymentId`
  (a primeira, criada pelo BE-11, ou qualquer uma nunca cobrada) e tentativa
  `PENDENTE` com `proximaTentativaEm <= now()` (criada pelo webhook BE-13 apos
  uma recusa).
- A seleçao trava as linhas para impedir que duas instancias cobrem a mesma
  tentativa ao mesmo tempo (`FOR UPDATE SKIP LOCKED`), no mesmo molde do
  `OutboxPublisher` do Assinatura.
- A chamada ao gateway usa `POST /v1/payments` com `Idempotency-Key =
  renovacaoId:numero` e `externalReference = renovacaoId`. Reutiliza o WebClient
  existente, so exige uma variante do client (metodo novo) porque o atual
  codifica `assinaturaId` em ambos os campos e nao serve para renovacao.
- Em caso de aceite do gateway (`PENDING`, status inicial de toda cobranca), o
  scheduler registra o `paymentId` retornado na `TentativaCobranca` e encerra
  seu trabalho para aquela tentativa. Nao decide `APPROVED`/`REJECTED`: isso e
  papel do webhook (BE-13), que chega assincrono.
- Falha tecnica do gateway (timeout, 5xx, indisponibilidade) nao consome a
  tentativa: o scheduler loga, deixa a `TentativaCobranca` em `PENDENTE` sem
  `paymentId` e encerra o ciclo. A mesma tentativa e re-selecionada no proximo
  tick do scheduler, com a mesma `Idempotency-Key` (que e imutavel por
  `renovacaoId:numero`), ate obter o `paymentId`. Sem codigo de retry, sem
  propriedades de backoff, sem suspensao da assinatura (decisao D-R4).
- `@EnableScheduling` habilitado no Pagamento Service (na classe de aplicaçao ou
  numa config nova), pois nao existe hoje.

### Should Have

- Propriedade externalizada para o intervalo do scheduler
  (`app.renovacao.scheduler-intervalo-ms`) no bloco `app:` do `application.yaml`,
  com default em `.env.example`.
- Encapsulamento do erro de WebClient numa exceçao de dominio propria do
  Pagamento, no molde do `PublicacaoIndisponivelException` do webhook, em vez de
  propagar `WebClientResponseException`/`ReadTimeoutException` direto.
- Logs estruturados por `renovacaoId` e `numero` da tentativa para
  rastreabilidade (inicio da cobranca, aceite com `paymentId`, falha tecnica).
- Indice parcial em `tentativa_cobranca (proxima_tentativa_em) WHERE status =
  'PENDENTE'`, via migration `V5`, para evitar seq scan crescendo com a tabela.
  Essa lacuna foi apontada na revisao do BE-11 e cabe naturalmente nesta track.

### Out of Scope

- Decidir o resultado da cobranca (`APPROVED`/`REJECTED`) e publicar
  `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas` em
  `renovacao-resultado`. Isso e BE-13, pelo webhook do gateway.
- Criar a tentativa numero 2 ou 3 apos uma recusa. Essa decisao de negocio e do
  webhook (BE-13): ao receber `REJECTED` e a tentativa ser menor que 3, ele marca
  `RECUSADA` e agenda a proxima com `proximaTentativaEm`. O scheduler so cobra o
  que ja existe pronto.
- Definir a janela de backoff de negocio (D+1, D+3, D+7). Essa decisao aberta
  (O-R3) e do webhook, que grava o `proximaTentativaEm`. O scheduler apenas le o
  campo.
- Suspender a assinatura apos tres recusas. Decisao de negocio do Assinatura
  Service ao receber `RenovacaoTentativasEsgotadas` (BE-10).
- Reconciliaçao de cobranças `PENDING` abandonadas (webhook que nunca chega).
  Fora de escopo, igual ao 0.2.0 (decisao O-R4). Roadmap futuro.
- Tocar o mock do gateway. O gateway e agnostico a renovacao (decisao D-R2); o
  pagamento de renovacao reusa `POST /v1/payments`, sem mudança no mock.

## Constraints

- O contrato de eventos de renovaçao e travado (Gate 0 do plano). BE-12 nao
  publica eventos em Kafka; so chama o gateway e atualiza a `TentativaCobranca`.
  Os eventos de saida (`PagamentoRenovacaoAprovado`, `RenovacaoTentativasEsgotadas`)
  sao do BE-13.
- O schema e Flyway-owned (`ddl-auto: validate`). A proxima migration disponivel
  e `V5` (o Pagamento termina em `V4`). Nenhuma tabela nova: so o indice parcial
  em `tentativa_cobranca`, se incluido.
- A idempotencia no gateway depende da `Idempotency-Key = renovacaoId:numero`. O
  retry tecnico reusa a mesma chave para que o gateway devolva o mesmo
  `paymentId` em vez de criar uma segunda cobranca. Esse e o ponto que separa
  retry tecnico de retry de negocio: o primeiro nao incrementa `numero`, o
  segundo (webhook BE-13) cria tentativa nova com chave nova.
- Convençoes do projeto: Google Java Style, Javadoc obrigatorio em todo publico,
  CQRS Lite (command de escrita, leitura via repository), testes JUnit 5 com
  AssertJ (`@DisplayName`, `.as(...)`), `make verify` verde em
  `services/pagamento`.
- Concorrencia de scheduler: mais de uma instancia do Pagamento pode rodar em
  produçao. O `FOR UPDATE SKIP LOCKED` e necessario para impedir cobranca
  duplicada concorrente.

## Decisoes

- **Criterio de tentativa pronta para cobrar: `status = 'PENDENTE'` e ainda sem
  `paymentId`.** Resolvido. A tentativa numero 1 do BE-11 nasce com
  `proximaTentativaEm = null`. Em vez de tratar isso como caso especial, o
  criterio uniforme e: `PENDENTE` sem `paymentId` e cobrada imediatamente;
  `PENDENTE` com `paymentId` e uma cobranca ja feita (ignorada); e a futura
  `PENDENTE` com `proximaTentativaEm <= now()` (criada pelo BE-13) e cobrada na
  hora agendada. Um unico predicado cobre os dois caminhos: `status = 'PENDENTE'
  AND payment_id IS NULL AND (proxima_tentativa_em IS NULL OR
  proxima_tentativa_em <= now())`.
- **Variante do client, nao reuso do `criarCobranca`.** Resolvido (D-R2). O
  metodo atual codifica `assinaturaId` em `Idempotency-Key` e `externalReference`
  e e do fluxo de adesao. A renovacao exige `renovacaoId:numero` e `renovacaoId`.
  Um metodo novo `criarCobrancaRenovacao(renovacaoId, numero, valor)` no
  `GatewayPagamentoClient` mantem os dois fluxos isolados e legiveis.
- **Retry tecnico e de negocio nao se misturam.** Resolvido (D-R4, §retries).
  O retry tecnico (timeout, 5xx) e absorvido pelo proximo tick do scheduler: a
  tentativa segue `PENDENTE` sem `paymentId` e e re-selecionada, com a mesma
  `Idempotency-Key` imutavel por `renovacaoId:numero`, sem incrementar `numero`.
  O retry de negocio (recusa) cria tentativa nova em dias, com chave nova, e e
  decidido pelo webhook (BE-13).
- **Scheduler nao decide status.** Resolvido. Apos o aceite do gateway
  (`PENDING`), o scheduler so grava o `paymentId` e termina. O resultado
  (`APPROVED`/`REJECTED`) vem assincrono pelo webhook, possivelmente horas
  depois. O processo que fez o `POST` ja encerrou.
- **Indice parcial na V5.** Resolvido. A revisao do BE-11 deixou a lacuna do
  indice em aberto, apontando para "V5 do BE-13 ou V5 propria do BE-12". Como o
  scheduler e o consumidor direto desse predicado, a V5 entra aqui.

## Acceptance Criteria

### Primeira tentativa e cobrada
- Given uma tentativa numero 1 em `PENDENTE` sem `paymentId` e sem
  `proximaTentativaEm`, when o scheduler roda, then chama o gateway com
  `Idempotency-Key = <renovacaoId>:1` e `externalReference = <renovacaoId>`, e ao
  receber `PENDING` registra o `paymentId` retornado na tentativa.

### Tentativa agendada e cobrada na hora certa
- Given uma tentativa `PENDENTE` sem `paymentId` e com `proximaTentativaEm` no
  passado (criada pelo webhook do BE-13 apos uma recusa), when o scheduler roda,
  then a cobra com `Idempotency-Key = <renovacaoId>:<numero>` e registra o
  `paymentId`.

### Tentativa ja cobrada nao e reprocessada
- Given uma tentativa `PENDENTE` que ja tem `paymentId` (aceite anterior do
  gateway, aguardando webhook decidir), when o scheduler roda, then ela nao entra
  na seleçao e nenhum novo `POST /v1/payments` e feito.

### Falha tecnica nao consome tentativa de negocio
- Given uma tentativa pronta para cobrar, when o gateway devolve timeout ou 5xx,
  then o scheduler loga, deixa a `TentativaCobranca` em `PENDENTE` sem
  `paymentId` e **nao** incrementa o `numero`, **nao** publica
  `RenovacaoTentativasEsgotadas`.

### Falha tecnica e re-cobrada no proximo tick
- Given uma tentativa que falhou (timeout, 5xx) e segue `PENDENTE` sem
  `paymentId`, when o scheduler roda de novo no ciclo seguinte, then ela e
  re-selecionada e cobrada novamente com a mesma `Idempotency-Key`. O gateway
  devolve o mesmo `paymentId` em vez de criar uma segunda cobranca.

### Concorrencia entre instancias
- Given duas instancias do Pagamento rodando o scheduler ao mesmo tempo, when
  ambas encontram a mesma tentativa pronta, then o `FOR UPDATE SKIP LOCKED`
  garante que so uma a processe; a outra a ignora sem cobranca duplicada.

### Scheduler nao decide resultado
- Given o BE-12 implementado, when o gateway aceita a cobranca (`PENDING`), then
  o scheduler so registra o `paymentId`; nenhum evento e publicado em
  `renovacao-resultado` (isso e BE-13) e nenhum `StatusTentativa` muda para
  `APROVADA`/`RECUSADA` (isso tambem e BE-13).

### Indice parcial mantem a seleçao enxuta
- Given a migration `V5` aplicada com o indice parcial, when o scheduler seleciona
  tentativas prontas, then a consulta usa o indice em
  `(proxima_tentativa_em) WHERE status = 'PENDENTE'` em vez de seq scan na tabela
  cheia.
