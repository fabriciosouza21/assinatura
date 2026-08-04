# PRD: Recuperacao de falha tecnica na adesao

Status: Aprovado
Data: 2026-08-04
Versao alvo: 0.6.0

## Contexto

A adesao tem um buraco de resiliencia. Quando o evento `AssinaturaSolicitada` e
consumido e o gateway esta indisponivel, tres camadas falham em sequencia e
deixam a assinatura presa em `AGUARDANDO_PAGAMENTO`:

1. Retry HTTP do WebClient (`GatewayRetryPolicy`, 3 tentativas com backoff
   exponencial + jitter): esgota e lanca `CobrancaGatewayIndisponivelException`.
2. Retry do consumer Kafka (`DefaultErrorHandler`, 3 tentativas): esgota e
   publica o evento em `assinatura-solicitada-dlq`.
3. `CriarCobrancaAdesao.processar` persiste a `Cobranca` somente apos o sucesso
   no gateway. Em falha tecnica, nada e gravado. Nenhum webhook volta e as
   unicas saidas de `AGUARDANDO_PAGAMENTO` (`ativar`, `recusarPagamento`)
   nunca disparam. A assinatura fica presa para sempre.

Esse gap e a decisao O-R4 do PRD `2026-07-31-scheduler-tentativas-cobranca.md`
("reconciliacao de cobrancas PENDING abandonadas... roadmap futuro"). O
mecanismo equivalente ja existe para a renovacao (`CobrancaRenovacaoScheduler`),
mas nao foi estendido para a adesao.

## Objetivo

Espelhar o modelo de retentativa da renovacao para a adesao: persistir a
intencao de cobranca antes de chamar o gateway, ter um scheduler que re-cobra
com `FOR UPDATE SKIP LOCKED` quando o gateway volta, e ao esgotar o teto de
falhas tecnicas publicar um evento que move a assinatura para um novo estado
terminal `PAGAMENTO_FALHOU`.

## Decisoes de design

| Decisao | Escolha |
|---|---|
| Objetivo | Retentativa automatica (espelhar renovacao) |
| Onde retentar | Scheduler no Pagamento Service |
| Destino ao esgotar | Novo status `PAGAMENTO_FALHOU` no Assinatura |
| Relacao com `PAGAMENTO_RECUSADO` | Convivem (recusa de gateway x falha tecnica esgotada) |
| Estrutura de dados | Tabela nova `cobranca_adesao_tentativa` |
| Backfill de assinaturas ja presas | Fora de escopo (so fluxo novo) |

## Maquina de estados da assinatura

### Estado atual

```
AGUARDANDO_PAGAMENTO --ativar()--> ATIVA --iniciarRenovacao()--> EM_RENOVACAO --renovar()--> ATIVA
        |
        +--recusarPagamento()--> PAGAMENTO_RECUSADO --solicitarCancelamento()--> CANCELADA
```

### Estado apos esta entrega

Adiciona uma terceira saida de `AGUARDANDO_PAGAMENTO`:

```
AGUARDANDO_PAGAMENTO --ativar()-----------------> ATIVA
        |
        +--recusarPagamento()--> PAGAMENTO_RECUSADO --+
        |                                            |
        +--falharPagamento()----> PAGAMENTO_FALHOU --+--> solicitarCancelamento() --> CANCELADA (imediato)
```

### O novo status `PAGAMENTO_FALHOU`

- Semantica: adesao que nao conseguiu ser cobrada apos esgotar o teto de falhas
  tecnicas do gateway. O cliente nunca teve a assinatura ativa, e o motivo nao
  e recusa de negocio (cartao recusado) e sim indisponibilidade prolongada do
  gateway.
- Origem unica: novo metodo `Assinatura.falharPagamento()`, invocado a partir
  de um novo evento consumido no Assinatura Service.
- Transicoes de saida: apenas `solicitarCancelamento() -> CANCELADA` imediato
  (mesma regra de `PAGAMENTO_RECUSADO`).
- Por que nao reusar `PAGAMENTO_RECUSADO`: `RECUSADO` carrega a semantica de
  "gateway avaliou e disse nao" (webhook REJECTED). `FALHOU` carrega "nao
  conseguimos nem perguntar ao gateway N vezes". Causas diferentes,
  observabilidade diferente, eventualmente fluxos de re-entrada diferentes.

### Metodo de dominio

```java
// Assinatura.java
void falharPagamento() {
  // exige AGUARDANDO_PAGAMENTO, lanca IllegalStateException caso contrario
  // this.status = PAGAMENTO_FALHOU
}
```

Mesmo shape de `recusarPagamento`: guarda de pre-condicao + transicao. Sem
logica de negocio alem da mudanca de estado.

### Enum StatusAssinatura

Acrescenta `PAGAMENTO_FALHOU` ao enum existente. A coluna `status` da tabela
`assinatura` e `VARCHAR`, entao nao ha alteracao de schema no Assinatura
Service. E uma migracao so de codigo.

## Modelo de dados no Pagamento

### Nova entidade CobrancaAdesaoTentativa

Tabela `cobranca_adesao_tentativa`, espelho da `tentativa_cobranca` de
renovacao, adaptado para o fluxo de adesao (1:1 cobranca-assinatura, sem
`numero`, sem backoff em dias):

```sql
CREATE TABLE cobranca_adesao_tentativa (
    id                    BIGSERIAL       PRIMARY KEY,
    assinatura_uuid       VARCHAR(36)     NOT NULL,
    valor                 NUMERIC(19,4)   NOT NULL,  -- valor da adesao, do evento AssinaturaSolicitada
    payment_id            VARCHAR(64),         -- nullable: nulo ate o gateway aceitar
    status                VARCHAR(32)     NOT NULL,  -- PENDENTE | COBRADA | ESGOTADA
    falhas_tecnicas       INTEGER         NOT NULL DEFAULT 0,
    proxima_tentativa_em  TIMESTAMP,           -- nullable: nulo na 1a, preenchido em falhas
    criado_em             TIMESTAMP       NOT NULL DEFAULT now(),
    atualizado_em         TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cobranca_adesao_tentativa_assinatura
    ON cobranca_adesao_tentativa (assinatura_uuid);

CREATE INDEX idx_cobranca_adesao_tentativa_pendente
    ON cobranca_adesao_tentativa (proxima_tentativa_em)
    WHERE status = 'PENDENTE' AND payment_id IS NULL;
```

O ponto chave que resolve o gap: persistir antes de chamar o gateway, com
`payment_id` nullable e `status = PENDENTE`. A tentativa nasce sem `paymentId`,
com ele preenchido so quando o gateway aceita.

### Relacao com a Cobranca existente

A `Cobranca` existente (correlacao assinatura<->`paymentId` pos-gateway)
permanece intocada. As duas tabelas coexistem com papeis distintos:

- `cobranca_adesao_tentativa`: intencao de cobranca pre-gateway, com
  retentativa. Gerada pelo consumer de `AssinaturaSolicitada`. Vira `COBRADA`
  quando o gateway aceita.
- `cobranca`: correlacao assinatura<->`paymentId` pos-gateway. Gerada pelo
  scheduler quando a chamada ao gateway tem sucesso. O webhook continua lendo
  esta tabela exatamente como hoje.

### Primitiva de concorrencia

Mesmo padrao da renovacao. Query de selecao:

```sql
SELECT * FROM cobranca_adesao_tentativa
 WHERE status = 'PENDENTE'
   AND payment_id IS NULL
   AND (proxima_tentativa_em IS NULL OR proxima_tentativa_em <= now())
 ORDER BY id
 LIMIT 200
 FOR UPDATE SKIP LOCKED;
```

Permite multiplas instancias do Pagamento sem cobranca duplicada.

## O CobrancaAdesaoScheduler

Espelho estrutural do `CobrancaRenovacaoScheduler`. Mesmo shape, sem o conceito
de `numero`/backoff de negocio (adesao nao retenta por recusa, so por falha
tecnica):

```java
@Component
class CobrancaAdesaoScheduler {

  @Scheduled(
      initialDelayString = "${app.adesao.scheduler-delay-inicial-ms:0}",
      fixedDelayString = "${app.adesao.scheduler-intervalo-ms:5000}")
  void cobrar() {
    List<CobrancaAdesaoTentativa> prontas = repository.buscarProntasParaCobrar();
    for (tentativa : prontas) {
      try {
        CobrancaCriada criada = gatewayClient.criarCobranca(assinaturaUuid, valor);
        tentativa.registrarCobranca(criada.paymentId());   // status COBRADA, zera falhasTecnicas
        // grava Cobranca (correlacao pos-gateway, como hoje)
      } catch (CobrancaGatewayIndisponivelException e) {
        tentativa.registrarFalhaTecnica();                 // falhasTecnicas++
        if (tentativa.esgotouFalhasTecnicas(teto)) {
          tentativa.esgotar();                             // status ESGOTADA
          gravarOutboxEsgotamento(tentativa);              // publica AssinaturaAdesaoEsgotada
        }
        // else: proxima_tentativa_em = now + backoff, segue PENDENTE
      }
      repository.save(tentativa);
    }
  }
}
```

## Configuracao externalizada

Mesma convencao do `app.renovacao.*` existente. Novo prefixo `app.adesao.*`:

```yaml
app:
  adesao:
    scheduler-intervalo-ms: ${APP_ADESAO_SCHEDULER_INTERVALO_MS:5000}
    scheduler-delay-inicial-ms: ${APP_ADESAO_SCHEDULER_DELAY_INICIAL_MS:0}
    teto-falhas-tecnicas: ${APP_ADESAO_TETO_FALHAS_TECNICAS:3}
    backoff-falhas-tecnicas-ms: ${APP_ADESAO_BACKOFF_FALHAS_TECNICAS_MS:60000}
```

Defaults alinhados com a renovacao onde aplicavel: intervalo 5s, teto 3. O
backoff aqui e em milissegundos (60s), nao em dias como a renovacao, porque a
recuperacao do gateway e um evento de minutos/horas, nao de dias. Tres falhas
tecnicas com 60s de backoff cobre uma indisponibilidade de cerca de 3 minutos
antes de esgotar.

## Orquestracao no consumer de AssinaturaSolicitada

Hoje o `CriarCobrancaAdesao.processar` chama o gateway e persiste a `Cobranca`
pos-sucesso. A partir desta entrega, o consumer persiste a tentativa antes do
gateway e nao chama o gateway (o scheduler assume essa responsabilidade):

1. Valida idempotencia (`existsByAssinaturaUuid`).
2. Cria `CobrancaAdesaoTentativa` `PENDENTE` no banco (`paymentId` null).
3. Nao chama o gateway. Retorna.

Isso elimina o `CobrancaGatewayIndisponivelException` no consumer, e portanto
elimina o caminho para a DLQ por falha tecnica. O evento `AssinaturaSolicitada`
deixa de precisar de retentativa no consumer: ou a tentativa foi criada
(sucesso do consumer) ou nao foi (excecao de banco, que ainda vai para DLQ).

## Evento de esgotamento

### Contrato AssinaturaAdesaoEsgotada

```java
public record AssinaturaAdesaoEsgotada(
    UUID eventId,
    Instant ocorridoEm,
    UUID assinaturaId
) {}
```

### Topico novo adesao-resultado

Topico dedicado `adesao-resultado`, isolado, consumer dedicado. Mantem o
principio de cada fluxo no seu canal (D-R2).

`KafkaTopicsConfig` ganha `adesao-resultado` com a mesma convencao das demais:
3 particoes, replicacao 1, mais a DLQ `adesao-resultado-dlq` com retencao de 7
dias.

### Rota na outbox

A outbox ja roteia por `eventType -> topico`. Adicionamos:

```
AssinaturaAdesaoEsgotada -> adesao-resultado
```

Configuracao em `application.yaml` do Pagamento, mesma estrutura das rotas
existentes. Sem codigo de roteamento novo, so mapeamento.

## Consumo no Assinatura Service

### AdesaoResultadoConsumer

Novo consumer, espelho estrutural do `RenovacaoResultadoConsumer`:

```java
@Component
class AdesaoResultadoConsumer {

  @KafkaListener(
      id = "assinatura-adesao-resultado",
      groupId = "assinatura",
      topics = "${app.kafka.topico-adesao-resultado}")
  void consumir(String payload, Header eventId) {
    // 1. Desserializa AssinaturaAdesaoEsgotada
    // 2. Valida payload
    // 3. Idempotencia por eventId (PagamentoEventoProcessadoRepository.existsByEventId)
    // 4. Delega a FalharPagamentoAdesao.executar(evento)
  }
}
```

### Command FalharPagamentoAdesao

Espelho do `ConfirmarPagamentoAdesao`, `@Transactional`, com as mesmas guardas.

Nota sobre o `ConfirmarPagamentoAdesao` existente: ele hoje trata o caso de
assinatura ja em estado terminal (ex: `PAGAMENTO_RECUSADO`) como no-op (log
WARN, registra idempotencia, evita DLQ). O mesmo tratamento precisa cobrir
`PAGAMENTO_FALHOU` no caso de race (webhook `APPROVED`/`REJECTED` chegando
apos `AssinaturaAdesaoEsgotada`). Essa e uma pequena extensao no
`ConfirmarPagamentoAdesao`, listada no resumo de mudancas do Assinatura.

```java
@Transactional
class FalharPagamentoAdesao {

  void executar(AssinaturaAdesaoEsgotada evento) {
    // 1. Lock pessimista na Assinatura (SELECT FOR UPDATE)
    // 2. Idempotencia: PagamentoEventoProcessadoRepository.existsByEventId(eventId)
    //    - se ja processado, return
    // 3. Busca assinatura por uuid
    //    - se nao existe, return (permite redelivery quando ela surgir)
    // 4. assinatura.falharPagamento()  -> AGUARDANDO_PAGAMENTO -> PAGAMENTO_FALHOU
    // 5. grava PagamentoEventoProcessado (eventId)
    // 6. (nao publica nada na outbox; PAGAMENTO_FALHOU e terminal sem evento de saida)
  }
}
```

Idempotencia pelo mesmo mecanismo do `ConfirmarPagamentoAdesao`
(`PagamentoEventoProcessadoRepository`, `DataIntegrityViolationException`
para dedup concorrente). Reusa a tabela `pagamento_evento_processado`
existente, sem schema novo no Assinatura.

## Fluxo completo

```
[Gateway indisponivel]
   |
   v
AssinaturaSolicitada (Kafka)
   -> Consumer cria CobrancaAdesaoTentativa(PENDENTE) -> ACK
   |
   v
CobrancaAdesaoScheduler (5s)
   -> seleciona PENDENTE (FOR UPDATE SKIP LOCKED)
   -> gateway falha 1a vez: falhasTecnicas=1, proximaTentativa=now+60s
   -> gateway falha 2a vez: falhasTecnicas=2, proximaTentativa=now+60s
   -> gateway falha 3a vez: falhasTecnicas=3 = teto
      -> esgotar() -> ESGOTADA
      -> grava AssinaturaAdesaoEsgotada na outbox
   |
   v
OutboxPublisher -> topico adesao-resultado
   |
   v
AdesaoResultadoConsumer (Assinatura)
   -> FalharPagamentoAdesao.executar
   -> assinatura.falharPagamento()
   |
   v
AGUARDANDO_PAGAMENTO -> PAGAMENTO_FALHOU   [estado terminal ate cancelamento]
```

### Caso "gateway volta no meio"

Se o gateway se recupera antes do teto (ex: na 2a tentativa), o scheduler chama
com sucesso:

```
tentativa.registrarCobranca(paymentId) -> COBRADA, falhasTecnicas zerado
grava Cobranca (correlacao pos-gateway, como hoje)
```

Dai em diante o fluxo de webhook tradicional assume: webhook APPROVED -> ATIVA,
webhook REJECTED -> PAGAMENTO_RECUSADO. A retentativa cumpriu seu papel.

## Observabilidade

Seguindo a convencao do projeto (`docs/guidelines/logging.md`), logs novos no
scheduler, com `event` estavel em snake_case:

- DEBUG: retry, idempotencia, decisoes tecnicas por tentativa (mesma regra da
  renovacao).
- INFO: tentativa cobrada com sucesso (`event: adesao_cobranca_criada`),
  esgotamento (`event: adesao_tentativas_esgotadas`).
- WARN: falha tecnica registrada (`event: adesao_falha_tecnica_gateway`),
  degradacao recuperavel.

Sem logar `paymentId`, payloads ou excecao.

## Estrategia de testes

Seguindo a convencao do projeto (JUnit 5 + AssertJ, `@DisplayName`, `.as(...)`).

### Testes de dominio (Assinatura)

- `Assinatura.falharPagamento()`: transita `AGUARDANDO_PAGAMENTO ->
  PAGAMENTO_FALHOU`.
- `Assinatura.falharPagamento()` em estado invalido (`ATIVA`,
  `PAGAMENTO_RECUSADO`, etc.): lanca `IllegalStateException`.
- `Assinatura.solicitarCancelamento()` a partir de `PAGAMENTO_FALHOU`:
  transita para `CANCELADA` imediato (efeito `IMEDIATO`).

### Testes de dominio (Pagamento)

- `CobrancaAdesaoTentativa`:
  - `registrarFalhaTecnica()` incrementa `falhasTecnicas`.
  - `registrarCobranca(paymentId)` zera `falhasTecnicas`, seta `paymentId`,
    status `COBRADA`.
  - `esgotouFalhasTecnicas(teto)` no limite e acima do limite.
  - `esgotar()` transita para `ESGOTADA`.
  - Pre-condicoes: `registrarCobranca` exige `PENDENTE`; `esgotar` exige
    `PENDENTE`.

### Testes unitarios do scheduler

- `CobrancaAdesaoScheduler.cobrar()` com gateway mockado:
  - Gateway OK: chama `registrarCobranca`, persiste `Cobranca`, persiste
    tentativa `COBRADA`.
  - Gateway falha abaixo do teto: persiste tentativa `PENDENTE` com
    `proximaTentativaEm` no futuro, nao chama outbox.
  - Gateway falha no teto: persiste tentativa `ESGOTADA`, chama `gravarOutbox`
    com `AssinaturaAdesaoEsgotada`.
  - Sucesso apos falhas: zera `falhasTecnicas`.

### Testes unitarios do command (Assinatura)

- `FalharPagamentoAdesao.executar`:
  - Assinatura em `AGUARDANDO_PAGAMENTO`: transita para `PAGAMENTO_FALHOU`,
    grava idempotencia.
  - Evento repetido (`eventId` ja processado): idempotente, nao transita de
    novo.
  - Assinatura inexistente: return silencioso (permite redelivery).

### Testes de integracao (`@Tag("integration")`)

Rodam via `make test-integration`, recriando o Postgres:
- Scheduler seleciona com `FOR UPDATE SKIP LOCKED` sob concorrencia (duas
  threads, mesmo registro, so uma cobra).
- Consumer `AdesaoResultadoConsumer` com `@EmbeddedKafka`: publica
  `AssinaturaAdesaoEsgotada`, assinatura termina em `PAGAMENTO_FALHOU`.
- Rota outbox: `AssinaturaAdesaoEsgotada` chega ao topico `adesao-resultado`.

### Casos negativos cobertos pelo design

- Webhook `APPROVED`/`REJECTED` chegando apos `PAGAMENTO_FALHOU`: o
  `ConfirmarPagamentoAdesao` usa lock pessimista e guarda de estado.
  `ativar()`/`recusarPagamento()` exigem `AGUARDANDO_PAGAMENTO`; partindo de
  `PAGAMENTO_FALHOU` lancam `IllegalStateException`. O consumer trata a excecao
  como no-op (log WARN) e registra idempotencia, evitando DLQ. Esse
  comportamento ja existe para `PAGAMENTO_RECUSADO`; espelhamos.
- Evento `AssinaturaAdesaoEsgotada` chegando antes da assinatura existir:
  idempotente, nao registra, permite redelivery.

## Fora de escopo

- Backfill de assinaturas ja presas em `AGUARDANDO_PAGAMENTO`.
- TTL/expiracao de cobrancas `PENDING` abandonadas (a O-R4 original, na sua
  forma de "webhook que nunca chega"). Este design trata so do sub-caso
  "falha tecnica ao criar a cobranca". O cenario "cobranca criada com sucesso,
  gateway aceitou como PENDING, mas o webhook nunca volta" permanece fora de
  escopo.
- Reversao de `PAGAMENTO_FALHOU` para estado ativo. Terminal ate cancelamento,
  como `PAGAMENTO_RECUSADO`.
- Refatorar o webhook para ler `CobrancaAdesaoTentativa` em vez de `Cobranca`
  (as duas tabelas coexistem).
- Estender o `ReplayDlqConsumer` para `adesao-resultado-dlq`. O consumer novo
  ja cai na DLQ pelo `DefaultErrorHandler` padrao; replay permanece desligado
  por default, como hoje.
- Backoff em dias para adesao (a renovacao usa `1,3`). Adesao so retenta por
  falha tecnica, com backoff em minutos.
- Mock do gateway: intocado. O gateway e agnostico ao fluxo (D-R2).

## Riscos e mitigacoes

| Risco | Mitigacao |
|---|---|
| Duas instancias do scheduler cobrando a mesma tentativa | `FOR UPDATE SKIP LOCKED` (mesmo padrao da renovacao) |
| `AssinaturaAdesaoEsgotada` e webhook `APPROVED` chegam em ordem inversa (race) | Lock pessimista + guarda de estado na `Assinatura`. Quem chega depois encontra estado terminal e vira no-op. |
| Teto de falhas tecnicas muito apertado (3 x 60s = 3min) para indisponibilidade longa | Externalizado em `APP_ADESAO_TETO_FALHAS_TECNICAS` e `APP_ADESAO_BACKOFF_FALHAS_TECNICAS_MS`. Operacao ajusta sem deploy. |
| Polling do scheduler gerando carga se muitas tentativas `PENDENTE` acumularem | Lote de 200 por tick + indice parcial. Mesmo padrao da renovacao. |

## Resumo das mudancas por servico

### Pagamento Service

- Migration V12: tabela `cobranca_adesao_tentativa` + indice parcial.
- `CobrancaAdesaoTentativa` (entidade), `StatusTentativaAdesao` (enum),
  `CobrancaAdesaoTentativaRepository`.
- `CobrancaAdesaoScheduler` + query `buscarProntasParaCobrar`.
- Refatorar `CriarCobrancaAdesao`: persistir tentativa `PENDENTE`, nao chamar
  gateway.
- `AssinaturaAdesaoEsgotada` (record de contrato).
- Rota outbox + topico `adesao-resultado` + `adesao-resultado-dlq` em
  `KafkaTopicsConfig`.
- Config `app.adesao.*` em `application.yaml`.

### Assinatura Service

- `StatusAssinatura`: +`PAGAMENTO_FALHOU`.
- `Assinatura.falharPagamento()` + ramo em `solicitarCancelamento()`.
- `AdesaoResultadoConsumer`, `FalharPagamentoAdesao`.
- Extensao do `ConfirmarPagamentoAdesao` para tratar `PAGAMENTO_FALHOU` como
  no-op no caso de race (webhook chegando apos esgotamento).
- Config do topico em `application.yaml`.

## Versionamento

Bump de minor (0.5.0 -> 0.6.0) nos dois `pom.xml`, pela convencao do projeto
(recurso novo, sem quebra de contrato). Entrada no `CHANGELOG.md` em
`## [0.6.0] - YYYY-MM-DD` com `### Adicionado`.
