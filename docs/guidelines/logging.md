# Guideline de Logging

Aplica-se a `services/assinatura` e `services/pagamento` (Spring Boot 4.1 / Java 26).
Documenta o uso **atual** e estabelece regras **obrigatórias** para novos logs.
Linguagem intencionalmente direta: este é um documento de aplicação, não um tutorial.

O mock em Go (`docker/mock-pagamento/`) **não segue** este guideline. Ele usa a
stdlib `log` e tem regras próprias.

---

## 1. Stack

- **SLF4J + Logback**, transitivos do `spring-boot-starter` (Spring Boot 4.1.0).
- **Sem Lombok.** O logger é sempre declarado manualmente.
- **Sem Log4j2. Sem JSON/structured logging.** Os logs saem em **texto puro**, uma
  linha por evento, no console (`stdout`).

### Declaração do logger (obrigatório)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MinhaClasse {

  private static final Logger log = LoggerFactory.getLogger(MinhaClasse.class);

  // ...
}
```

- Campo sempre `private static final`, sempre nomeado `log`.
- Sempre `LoggerFactory.getLogger(NomeDaClasse.class)` (nunca string com o nome).
- **Proibido** `@Slf4j` do Lombok (Lombok não está no classpath).

---

## 2. Configuração atual

### 2.1 Sem `logback-spring.xml`

Não existe arquivo de configuração do Logback nos serviços. A aplicação usa a
configuração **default** do Spring Boot Logback Starter: console em `stdout`,
texto puro, pattern default com cores ANSI.

O único `log4j.properties` do repositório (`docker/kafka/log4j.properties`) é do
broker Kafka e **não interfere** na aplicação.

### 2.2 Níveis por `application.yaml`

Configuração **idêntica** nos dois serviços (`services/assinatura/src/main/resources/application.yaml:91-98`
e `services/pagamento/src/main/resources/application.yaml:71-78`):

```yaml
logging:
  level:
    root: INFO
    com.globo: INFO
    org.apache.kafka: WARN
    org.springframework: WARN
    org.hibernate.orm: WARN
    org.flywaydb: WARN
```

- O namespace da aplicação é `com.globo` (pacote raiz de ambos os serviços).
- **Não há** `logging.pattern.*`, `logging.file.*` nem JSON.
- **Não há perfis de logging por ambiente.** Não existe `application-prod.yaml`.
  Em produção roda-se a configuração `dev` a menos que variáveis de ambiente
  sobreponham.

### 2.3 Tracing (OpenTelemetry)

Ambos os serviços têm OTel habilitado (`spring-boot-starter-opentelemetry`):

- `management.tracing.sampling.probability: 1.0` (100% amostrado).
- Export OTLP para Jaeger (`http://jaeger:4318/v1/traces`).
- `spring.kafka.listener.observation-enabled: true` e
  `spring.kafka.template.observation-enabled: true` (instrumenta Kafka).
- `OTEL_SERVICE_NAME` definido por serviço no `docker-compose.yml`.

**Gap conhecido:** como o pattern de log default não inclui `%X{traceId}`, o
`traceId`/`spanId` do OTel **não aparece** nas linhas de log em texto puro. A
correlação log↔trace no Jaeger fica indisponível. Ver seção 9 (recomendações).

---

## 3. Mensagens — regras obrigatórias

### 3.1 Sempre em PT-BR, sem acentos

Convenção **implícita e 100% aplicada** hoje. Mensagens com acento quebram
`grep`/parsing e padronizam a busca.

```java
// CERTO
log.info("Solicitacao de assinatura recebida para usuarioId={} e plano={}", ...);

// ERRADO
log.info("Solicitação de assinatura recebida...", ...);          // acento
log.info("Subscription request received for userId={}", ...);     // inglês
```

### 3.2 Placeholders `{}` — sempre. Nunca concatenação.

```java
// CERTO
log.info("Cobranca criada no gateway para assinaturaId={} com httpStatus={}",
    assinaturaId, response.getStatusCode().value());

// ERRADO
log.info("Cobranca criada para " + assinaturaId);                 // concatenação
log.info(String.format("Cobranca para %s", assinaturaId));        // String.format
log.info("Cobranca para %s", assinaturaId);                       // %s no SLF4J não funciona
```

Motivo: placeholders preservam a estrutura da mensagem, permitem lazy
formatting (a string só é montada se o nível estiver ativo) e facilitam parsing.

### 3.3 Chave-valor na mensagem

Padrão `chave={}` para todo parâmetro. Facilita busca e leitura.

```java
// CERTO — parâmetros nomeados
log.info("Webhook recebido para eventId={} assinaturaId={} paymentId={}",
    eventId, assinaturaId, paymentId);

// RUIM — parâmetros posicionais sem contexto
log.info("Webhook recebido: {} {} {}", eventId, assinaturaId, paymentId);
```

---

## 4. Níveis — quando usar cada

| Nível | Quando | Stack trace? | Exemplo canônico |
|-------|--------|--------------|------------------|
| `error` | Falha de sistema ou bug. Catch-all `Exception.class`. | **Sempre** (exceção como último arg) | `log.error("Excecao nao tratada na requisicao", ex);` |
| `warn`  | Falha **esperada** ou **recuperável** (retry, idempotência, degradação). | Conforme seção 4.3 | `log.warn("Falha ao publicar evento {}", id, e);` |
| `info`  | Evento de domínio, marco de operação, integração externa. | Nunca | `log.info("Status de pagamento processado para assinaturaId={} com statusFinal={}", ...);` |
| `debug` | Detalhe de troubleshooting (decisão de idempotência, rota, payload parcial). | Opcional | `log.debug("Evento eventId={} ja processado, ignorando", eventId);` |
| `trace` | Granularidade muito fina (raramente necessário). | Opcional | — |

### 4.1 `info` — eventos de negócio e marcos

Logar quando algo **de domínio** acontece: assinatura solicitada, pagamento
processado, webhook recebido, cobrança criada no gateway, renovação criada,
publicação outbox bem-sucedida.

Exemplos reais do código:

```java
// AssinaturaController.java:46 — início de operação REST
log.info("Solicitacao de assinatura recebida para usuarioId={} e plano={}",
    request.usuarioId(), request.plano());

// PagamentoStatusConsumer.java:49 — evento de domínio processado
log.info("Status de pagamento processado para assinaturaId={} com statusFinal={}",
    evento.assinaturaId(), evento.status());

// GatewayPagamentoClient.java:51 — integração externa com status
log.info("Cobranca criada no gateway para assinaturaId={} com httpStatus={}",
    assinaturaId, response.getStatusCode().value());
```

**Regra:** `info` não deve ser "ruído de startup" nem log de cada linha. Se a
mensagem não representa um evento de domínio ou um marco observável, é `debug`.

### 4.2 `error` — sempre com a exceção como último argumento

A exceção **deve** ser o último argumento (sem placeholder). Assim o Logback
imprime o stack trace completo.

```java
// CERTO — stack trace impresso
log.error("Excecao nao tratada na requisicao", ex);

// CERTO — com contexto adicional + exceção
log.error("Falha de publicacao ou consulta ao gateway", e);

// ERRADO — exceção dentro do placeholder vira toString(), sem stack
log.error("Erro: {}", e);                    // só imprime e.toString()
log.error("Erro: " + e.getMessage());        // sem stack, com concatenação
```

Casos que **já aplicam** isto corretamente:

- `ProblemExceptionHandler.java:51` — `log.error("Excecao nao tratada na requisicao", ex);`
- `WebhookExceptionHandler.java:40` — `log.error("Falha de publicacao ou consulta ao gateway", e);`
- `WebhookExceptionHandler.java:53` — `log.error("Excecao nao tratada no processamento do webhook", e);`

### 4.3 `warn` — decisão sobre stack trace (fixar convenção)

Hoje há **inconsistência**: algumas chamadas passam a exceção (geram stack),
outras passam só `getMessage()` (sem stack).

```java
// ProcessarPagamento.java:85 — passa 'e' como último arg → COM stack trace
log.warn("Evento eventId={} da assinaturaId={} ja foi registrado por uma transacao concorrente"
    + " (provavel rebalance do consumer); tratando save como no-op idempotente",
    evento.eventId(), assinatura.getUuid(), e);

// RenovacaoScheduler.java:89 — passa só getMessage() → SEM stack trace
log.warn("Falha ao processar assinatura {}: {}", assinatura.getId(), e.getMessage());
```

**Regra:** para falhas esperadas/recuperáveis (idempotência por concorrência,
retry de outbox), o stack trace geralmente é ruído. **Decida caso a caso:**

- Falha **esperada** e tratada (idempotência, rebalance, conflito) → `warn`
  **com `e.getMessage()` no placeholder**, sem stack. A condição em si é a
  informação; o stack não ajuda.
- Falha **anômala** que será retryada mas pode indicar problema (outbox após N
  tentativas, gateway instável) → `warn` **com a exceção como último arg**,
  para diagnóstico.

### 4.4 `debug` / `trace` — ausentes hoje, incentivados em pontos específicos

Hoje **zero** `debug`/`trace` em produção. Recomendado introduzir em:

- Decisões de idempotência (por que ignorei este evento?).
- Início/fim de ciclo de schedulers (lote retornado, itens processados).
- Seleção de rota Kafka (`AssinaturaSolicitada` vs `RenovacaoSolicitada`).
- Early-returns que hoje são silenciosos (ver seção 7).

```java
// Exemplo recomendado para early-return silencioso
log.debug("Evento eventId={} para assinaturaId={} ignorado: status atual=PENDING",
    evento.eventId(), evento.assinaturaId());
```

Em `dev`/`prod` continuam desligados (root=INFO). São ativados sob demanda por
namespace (`logging.level.com.globo.assinatura.assinatura: DEBUG`) sem reimplantar.

---

## 5. Tratamento de exceções — regra central

> **Toda exceção capturada deve ser logada, a menos que seja deliberadamente
> engolida e o motivo esteja documentado em comentário.**

### 5.1 `@ControllerAdvice` — duas categorias

**Catch-all e falhas de sistema: logar em ERROR.** Já é a prática.

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Problem> handleNaoTratada(Exception ex) {
  log.error("Excecao nao tratada na requisicao", ex);   // ← sempre logar
  return ResponseEntity.status(500).body(...);
}
```

**Exceções de domínio (4xx de negócio): NÃO logar.** Decisão deliberada de
segurança — o corpo da resposta é vazio justamente para **não vazar
identificadores** em logs. Isto é uma **exceção permitida** à regra acima e
precisa ser mantida.

Comentários documentais já existem:

- `AssinaturaExceptionHandler.java:11-13`
- `CobrancaExceptionHandler.java:11-12`
- `BadCredentialsExceptionHandler.java:13-15`

Não logue nestes casos:

- `AssinaturaNaoEncontradaException`, `AssinaturaJaExisteException` (404/409).
- `BadCredentialsException` (401) — não revelar qual condição falhou (evita
  enumeração de usuários).
- `CobrancaNaoEncontradaException` (404).
- `MethodArgumentNotValidException` (400) — erros de validação vão no corpo.

### 5.2 Silent catches — proibidos

Capturar e engolir sem logar é o antipadrão mais problemático. Pontos
**ativos** a corrigir:

- `JwtService.isValid()` (`security/JwtService.java:75`)
  ```java
  catch (JwtException | IllegalArgumentException ignored) { return false; }
  ```
  Token inválido/expirado/malformado fica invisível. Em incidente de auth não
  há como distinguir as três causas. Logar em `debug` (é esperado em fluxo
  normal, mas observável para diagnóstico):
  ```java
  catch (JwtException | IllegalArgumentException e) {
    log.debug("Token JWT rejeitado: {}", e.getMessage());
    return false;
  }
  ```

- `ProcessarWebhookPagamento.publicar()` (linhas 130-135) e
  `consultarStatus()` (linhas 109-111): capturam exceções e relançam
  `PublicacaoIndisponivelException` **descartando a causa original**. O stack
  trace real da falha de Kafka/gateway se perde. Logar `warn` com a exceção
  antes de relançar.

- `ProcessarWebhookPagamento.processar()` (linhas 100-102): dedup de `eventId`
  por `DataIntegrityViolationException` é **silencioso**, ao passo que o
  equivalente no `ProcessarPagamento.java:85` **loga `warn`**. Alinhar com o
  `ProcessarPagamento` para consistência entre serviços.

---

## 6. Dados sensíveis (PII)

Hoje **nenhuma** exposição. O domínio trata UUIDs (`usuarioId`, `assinaturaId`,
`paymentId`, `eventId`), enums de status (`plano`, `statusFinal`,
`httpStatus`). Não há CPF, e-mail, token, senha ou cartão em logs.

### Regra

- **Proibido** logar PII: CPF, e-mail, número de cartão, CVV, token, senha,
  JWT, `Authorization`, cookies, payload de webhook assinado.
- Identificadores de domínio (UUIDs, status, planos) são permitidos.
- **Se** for introduzir log de payload/body de requisição (não existe hoje),
  exigir um sanitizer explícito. Não há sanitizer no projeto; ao introduzir,
  documentar neste guideline.
- O comentário anti-vazamento nos handlers de domínio (seção 5.1) **deve ser
  preservado**. Qualquer mudança que introduza log nesses handlers precisa de
  revisão de segurança.

---

## 7. Inconsistências ativas entre os serviços

Estas são divergências **reais** a corrigir ao tocar no código. Não são
bloqueadoras para novos logs, mas novos logs devem seguir a forma **canônica**
indicada.

| Ponto | Assinatura | Pagamento | Forma canônica |
|-------|-----------|-----------|----------------|
| Consumer Kafka loga consumo | `PagamentoStatusConsumer.java:49` loga INFO | `RenovacaoSolicitadaConsumer.java` **não tem logger** | Todo consumer loga INFO ao consumir |
| Controller loga entrada | `AssinaturaController.java:46` loga | `CobrancaController`, `WebhookPagamentoController` **não logam** | Logar entrada em controllers (com IDs, sem payload) |
| Producer Kafka loga sucesso | `OutboxPublisher.java:85` loga | `ProcessarWebhookPagamento.publicar()` **não loga** | Logar INFO em publicação bem-sucedida |
| Dedup `eventId` | `ProcessarPagamento.java:85` loga `warn` | `ProcessarWebhookPagamento.java:100` silencioso | Logar `warn` (com `e.getMessage()`) |
| WARN com stack trace | `ProcessarPagamento.java:90` passa `e` | `RenovacaoScheduler.java:89` passa só `getMessage()` | Decidir por caso (seção 4.3) |
| Schedulers logam ciclo | — | — | Início/fim/contagem em `debug` (recomendado) |

### Early-returns silenciosos

`ProcessarPagamento.executar()` (linhas 62-73) tem três caminhos de "não fiz
nada" sem log: status PENDING, `eventId` já processado, assinatura inexistente.
O Javadoc justifica o silêncio do último caso, mas em runtime é invisível.
Recomendado `debug` nos três.

---

## 8. Por camada

### Controllers / REST
- Logar `info` na entrada de operações de escrita (POST/PUT/DELETE), com
  identificadores de domínio, **sem payload**.
- Consultas (`GET`) podem logar `info` na entrada ou só `debug` para não
  poluir.
- Não logar status HTTP de saída nem duração (isso é papel de observation/OTel).

### Services de domínio (Commands/Queries)
- Serviços puros de domínio **não** têm logger hoje (`SolicitarAssinatura`,
  `CriarCobrancaService`). Manter assim: a responsabilidade de logar eventos
  fica nos consumers/schedulers/controllers de orquestração. Domínio não loga.

### Kafka
- **Consumers**: logar `info` ao consumir (com `eventId`/`assinaturaId`).
  Logar `debug`/`info` ao concluir com sucesso.
- **Producers** (outbox ou direto): logar `info` em publicação bem-sucedida,
  `warn` em falha.
- Não logar offset/partição manualmente; o framework já instrumenta via OTel
  (`observation-enabled`).

### Exception handlers
- Catch-all e falhas de sistema: `error` com exceção (seção 5.1).
- Domínio: não logar (seção 5.1).

### Schedulers (`@Scheduled`)
- Logar `debug` no início/fim do ciclo e contagem de itens processados.
- Logar `info` por evento de domínio produzido, `warn` por falha isolada.

---

## 9. Recomendações de evolução (não obrigatórias para novos logs)

Listadas por impacto. Implementação fora do escopo deste guideline.

1. **Correlação trace↔log (maior impacto).** O OTel já está presente e amostra
   100%. Basta incluir `traceId`/`spanId` no pattern de log via MDC (populado
   automaticamente pelo Spring Boot OTel):
   ```yaml
   logging:
     pattern:
       level: "%5p [%X{traceId:-},%X{spanId:-}]"
   ```
   Sem isso, uma linha de log em produção não tem ponte para o trace no Jaeger.

2. **`application-prod.yaml` com logging diferenciado.** Hoje prod roda com a
   config `dev`. Considerar: `org.springframework` em `INFO` em prod (para ver
   startup), JSON encoder para ingestão em plataforma de logs, rolling file
   appender.

3. **Structured logging (JSON) em prod.** Via `logstash-logback-encoder`.
   Mantém texto puro em `dev` para legibilidade local.

4. **Padronizar WARN com/sem stack trace** (seção 4.3) por revisão dirigida,
   não por guideline abstrato.

5. **Sanitizer de payload** caso se decida logar corpos de requisição.

---

## 10. Checklist rápido para novos logs

Antes de abrir PR com um log novo, confirmar:

- [ ] Declarado como `private static final Logger log = LoggerFactory.getLogger(Classe.class);`
- [ ] Mensagem em PT-BR, **sem acentos**.
- [ ] Placeholders `{}`, sem concatenação nem `String.format`.
- [ ] Parâmetros no formato `chave={}`.
- [ ] Nível correto: `error` (falha de sistema), `warn` (esperada/recuperável),
      `info` (evento de domínio), `debug` (troubleshooting).
- [ ] Exceção como **último argumento** em `error` (e em `warn` quando o stack
      for necessário).
- [ ] **Nenhuma PII** (CPF, e-mail, token, senha, cartão, JWT, Authorization).
- [ ] Não é exceção de domínio 4xx num handler que não loga (seção 5.1).
- [ ] Não engoliu exceção em silêncio (seção 5.2).
