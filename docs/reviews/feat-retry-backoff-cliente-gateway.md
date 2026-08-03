# Code Review: `feat/retry-backoff-cliente-gateway`

**Data:** 2026-08-03
**Branch:** `feat/retry-backoff-cliente-gateway` (worktree `.worktrees/feat-retry-backoff-cliente-gateway`)
**Entregável:** BE-21. Retry com backoff exponencial + jitter no `GatewayPagamentoClient` do Pagamento Service.
**Diff:** 10 arquivos (4 novos), 785 inserções, 38 remoções.
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

Retry com backoff exponencial e jitter sobre falhas transitórias do gateway (timeout, 5xx, 408, 429)
nas três chamadas do `GatewayPagamentoClient` (`criarCobranca`, `criarCobrancaRenovacao`,
`consultarStatus`), antes de propagar `CobrancaGatewayIndisponivelException` ao domínio. Política via
`Retry.from` custom (multiplicador externalizável, ao invés do `Retry.backoff` do Reactor fixo em 2x).
Parâmetros em `app.gateway.retry.*` com fail-fast no boot; timeouts externalizados; counter Micrometer
`pagamento.gateway.retry.tentativas` tag `metodo`; log DEBUG `gateway_retry_tentativa` por tentativa.
Contrato BE-20 inegociável: uma falha técnica por chamada após exaustão, não por tentativa HTTP.

## Veredito

**Needs fixes.** O contrato BE-20 está preservado e a idempotência é segura (`Idempotency-Key` estável
entre tentativas, testado). Mas dois pontos cegos de validação (descobertos pelo auditor, perdidos pelos
três reviewers) violam o fail-fast prometido pelo PRD e, no caso de `Infinity`, podem travar o scheduler
indefinidamente dentro de `@Transactional`. O dead code do `catch (IllegalStateException)` dá falsa
impressão de robustez mascarada por teste que exercita política que a produção não usa. Os três são
correções mínimas (poucas linhas + testes RED).

## Critical

Nenhum.

## High

### [audit] `NaN`/`Infinity` passam pela validação e quebram o backoff silenciosamente

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryProperties.java:43-47`
`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryPolicy.java:116,110-112`

`validarParametros` checa `multiplicador < 1`. Em Java, `Double.NaN < 1` retorna `false` (NaN não é
ordenável), então `NaN` passa. O mesmo para `jitter`. O binder do Spring e o `application.yaml` não
rejeitam `NaN`/`Infinity` para um `double`.

Consequência em `calcularBackoff` (`GatewayRetryPolicy.java:110-112`):

- `baseMillis = backoffInicial.toMillis() * Math.pow(NaN, ...) = NaN`; `(long) NaN = 0` → cada backoff
  colapsa para `Duration.ofMillis(0)`: tempestade de retries sem atraso, derrotando o backoff e o
  anti-thundering-herd.
- `(long) Double.POSITIVE_INFINITY = Long.MAX_VALUE` →
  `Duration.ofMillis(Long.MAX_VALUE)` ≈ 292 milhões de anos, travando a thread do scheduler dentro de
  `@Transactional` (`CobrancaRenovacaoScheduler.cobrar()`) por tempo indeterminado.

Violação direta do Must Have do PRD: "Parâmetros de retry externalizáveis... com validação" e do
comportamento esperado de fail-fast. Achado do auditor; perdido pelos três reviewers.

- **Test (RED first):** `assertThatThrownBy(() -> new GatewayRetryProperties(3, 1, Double.NaN, 0.5))` e
  `(3, 1, Double.POSITIVE_INFINITY, 0.5)`. Falham hoje, passam com `Double.isFinite(multiplicador)`.
- **Fix:** fortalecer `validarParametros` e o construtor compacto do record com
  `if (!Double.isFinite(multiplicador) || multiplicador < 1)` e equivalente para `jitter`.

### [audit] `backoffInicialSegundos` não é validado

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryProperties.java:39-49`

O record valida `maxAttempts`, `multiplicador`, `jitter`, mas **não** `backoffInicialSegundos`.
`APP_GATEWAY_RETRY_BACKOFF_INICIAL_SEGUNDOS=0` (ou negativo) inicializa sem erro. Com `backoffInicial=0`,
`calcularBackoff` retorna `Duration.ofMillis(0)` para todas as tentativas: backoff efetivo zero, o que
derrotaria o jitter e o anti-thundering-herd que o PRD exige como acceptance criterion ("jitter... de
forma que as re-tentativas não ocorram todas no mesmo instante").

- **Test (RED first):** `assertThatThrownBy(() -> new GatewayRetryProperties(3, 0, 2.0, 0.5))` e
  `(3, -1, 2.0, 0.5)`.
- **Fix:** adicionar `if (backoffInicialSegundos < 1) throw new IllegalArgumentException(...)` no
  construtor compacto do record.

## Medium

### [quality/audit] `catch (IllegalStateException) isRetryExhausted` é dead code em produção

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayPagamentoClient.java:158-163`

Verificado no código real: o `Retry.from` custom (`GatewayRetryPolicy.java:66`) emite
`Mono.error(sinal.failure())` na exaustão: o erro ORIGINAL, não encapsulado em `RetryExhaustedException`.
`sinal.failure()` para 5xx/408/429 é `WebClientResponseException`, para timeout é
`WebClientRequestException`; ambos subclasses de `WebClientException`. Caem no `catch (WebClientException)`
da linha 156, **nunca** no `catch (IllegalStateException)` da linha 158.

O teste que aparenta cobri-lo (`devePropagarCobrancaGatewayIndisponivelAposEsgotarRetry`,
`GatewayPagamentoClientTest.java:183`) usa `Retry.backoff` do Reactor, que É um `RetrySpec` e wrap em
`RetryExhaustedException` (desempacotada para `IllegalStateException` em `.block()`). Falso positivo de
cobertura: o teste exercita um caminho que a produção não usa.

- **Test (RED first):** trocar `Retry.backoff(3, Duration.ofMillis(5))` por
  `GatewayRetryPolicy.criar(3, Duration.ofMillis(5))` no teste de exaustão. Continua verde via
  `catch (WebClientException)`, provando que o ramo `isRetryExhausted` é inalcançável pela política de
  produção.
- **Fix:** (a) remover o bloco `catch (IllegalStateException)` inteiro como dead code, **ou** (b)
  padronizar os 2 testes dissidentes em `GatewayRetryPolicy.criar` e documentar o catch como defesa
  contra um futuro swap de volta para `Retry.backoff`. Decisão no julgamento do implementador.

### [performance] Sem cap de backoff (`maxBackoff`)

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryPolicy.java:110`

`baseMillis = backoffInicial.toMillis() * Math.pow(multiplicador, tentativa - 1)` sem teto. Defaults
seguros (max-attempts=3, multiplicador=2.0 → máximo ~6s com jitter máximo). Mas como `multiplicador` e
`max-attempts` são externalizados sem teto, `max-attempts=8 + multiplicador=3.0` gera backoffs de até
~2187s (~36 min) por tentativa de retry. Combinado com o retry síncrono dentro de `@Transactional`
(ver Audit notes), um valor mal configurado pode levar a ticks de várias horas.

Recalibrado pelo auditor de High para Medium: os defaults de produção não acionam; só configuração
explícita e improvável. Vale como cinto-e-suspensório.

- **Test (RED first):** `calcularBackoff(8, Duration.ofSeconds(1), 3.0, 0.0, new Random(0))` e asserir
  que o resultado excede um teto documentado (ex.: 60s), justificando o `maxBackoff`.
- **Fix (opcional):** adicionar `app.gateway.retry.max-backoff-segundos` (default ~30s) aplicando
  `Math.min(backoff.toMillis(), maxBackoff.toMillis())` em `calcularBackoff`. Custo: 1 comparação por
  retry.

## Low

### [quality] 2 testes usam `Retry.backoff` em vez de `GatewayRetryPolicy.criar`

`services/pagamento/src/test/java/com/globo/pagamento/gateway/GatewayPagamentoClientTest.java:154,183`

Inconsistente com os outros 5 testes de retry, que usam `GatewayRetryPolicy.criar(...)`. Mascara a
cobertura do ramo dead (ver Medium acima). Padronizar em `GatewayRetryPolicy.criar`.

### [quality] `@DisplayName` "Nao deve..." em vez de "Deve nao..."

`services/pagamento/src/test/java/com/globo/pagamento/gateway/GatewayPagamentoClientTest.java:195,243`

Convenção do projeto (AGENTS.md e restante da suíte) usa "Deve" no início. Renomear para
"Deve nao retentar falha de negocio 4xx do gateway" e equivalente para 422.

### [quality] Falta teste direto para status 408

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryPolicy.java:130`
(`ehFalhaTransitoria`)

A condição é `status >= 500 || status == 408 || status == 429`, mas só 429 tem teste direto. Se alguém
trocar para `status == 429` apenas, 408 quebra sem teste pegar. Espelhar o teste de 429 enfileirando
`setResponseCode(408)` seguido de 201, asserindo `getRequestCount() == 2`.

### [quality] Log DEBUG sem tag `metodo`

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryPolicy.java:76-80`

O counter Micrometer é tagueado por `metodo` (`criarCobranca`/`criarCobrancaRenovacao`/`consultarStatus`),
mas o log DEBUG `gateway_retry_tentativa` não. Não dá para correlacionar um pico de counter com a entrada
de log correspondente sem correlação de timestamp. A política não recebe o `metodo` na criação. Para
adicionar, mover o log para o `doOnNext` em `bloquearComRetry` (onde o `metodo` está disponível)
centralizaria log + counter na mesma fronteira e melhoraria a coesão.

### [quality] `Retry.max(0).filter(ignored -> false)` redundante

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayPagamentoClient.java:35`

`Retry.max(0)` já desabilita o retry (zero retentativas); `.filter(ignored -> false)` duplica a
semântica. Nit. `Retry.max(0)` basta; se a intenção é dupla-defesa explícita, merece um comentário de
uma linha.

### [performance/quality] Wrapper de instrumentação alocado por chamada

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayPagamentoClient.java:148-154`

`Retry.from(...)` + lambda reconstruído a cada chamada de `bloquearComRetry` quando `meterRegistry !=
null` (sempre, em produção), inclusive no caminho feliz sem retry. Short-lived, comido por escape
analysis, amortizado contra uma chamada de rede de até 10s. Mover a instrumentação para dentro de
`GatewayRetryPolicy.criar` (recebendo `MeterRegistry` e tag `metodo` na criação) seria mais limpo.

### [quality] `validarParametros` re-executado a cada retry em `calcularBackoff`

`services/pagamento/src/main/java/com/globo/pagamento/gateway/GatewayRetryPolicy.java:109`

Defesa redundante após a validação eager em `criar`. Duas comparações baratas por tentativa. Precedente
do BE-20, mas sem teste que justifique mantê-la viva. Ou remover, ou adicionar teste direto em
`calcularBackoff` que valide a defesa.

## Good patterns

- **Fail-fast no boot** via `GatewayRetryProperties` (record com validação no construtor compacto).
- **`Random` injetável em `calcularBackoff`**: `ThreadLocalRandom` em produção, `Random(seed)` em teste;
  função pura testável sem sacrificar a aleatoriedade em runtime.
- **`bloquearComRetry` extraído na 3ª ocorrência** (regra de três respeitada), DRY limpo, genérico
  `<T>`, preserva o contrato de exceções.
- **`Idempotency-Key` verificado entre tentativas** (`GatewayPagamentoClientTest.java:163-170`):
  corretude de segurança que muitos times esquecem.
- **Counter Micrometer com cardinalidade de tag fixa em 3** (`criarCobranca`/`criarCobrancaRenovacao`/
  `consultarStatus`), sem risco de explosão de séries.
- **Filtro de transitórios correto e testado**: 4xx/recusa não retried, cobertura para 400/422/429/
  500/timeout.
- **Commits granulares Conventional PT-BR** (13 commits revisáveis isoladamente).
- **Logs Fluent API** com `event` snake_case estável, valores dinâmicos via `addKeyValue`, sem PII/
  payloads/`exception.getMessage()`.
- **Externalização completa** dos timeouts antes hardcoded, com defaults via env no `application.yaml` e
  `.env.example`.

## Audit notes

- **Drop (falso positivo):** Security [LOW] "cadeia de causa em `CobrancaGatewayIndisponivelException`
  carrega body bruto do gateway". Pré-existente, não regressão deste PR, sem diff para remediar aqui.
  `CobrancaGatewayIndisponivelException.java:18-20` é inalterado pelo diff. A mensagem da exceção é
  estática (`"cobranca_gateway_indisponivel"`), alinhada à convenção de logging.
- **Recalibrado de High para Medium-documentado:** Performance "retry dentro de `@Transactional` agrava
  janela de lock DB". O `CobrancaRenovacaoScheduler.cobrar()` é `@Transactional` com `FOR UPDATE SKIP
  LOCKED` propositalmente (PRD BE-12: previne cobrança dupla concorrente). Mover a chamada ao gateway
  para fora da transação abriria race de dupla cobrança, regressão pior que a janela de lock estendida.
  A matemática do reviewer é correta (~50s/tentativa com defaults, ~167 min/transação em outage total
  de 200 tentativas), mas bloquear o merge por algo que o PRD deliberadamente manteve colidiria com
  decisão de design travada. **Documentar como risco de observabilidade rastreado**, não corrigir
  aqui. Item candidato a entregável separado (já listado no PRD como Out of Scope).
- **Verificado clean:** contrato BE-20 (uma falha técnica por chamada após exaustão), `Flux.empty()` não
  ocorre em produção (a política sempre emite `Mono.error` ou `Mono.delay`), `MapPropertySource` +
  `ConfigurationPropertySources.from()` correto para Boot 4.1, sem PII em logs, sem injection sinks
  (`paymentId` em template URI é URL-encoded pelo WebClient; `metodo` na tag é literal), cardinalidade
  de tags Micrometer fixa, `ThreadLocalRandom` sem contenção, jitter simétrico correto (média
  preservada).
- **Correção de número:** o cálculo de backoff no pior caso com multiplicador alto usa base × (1+jitter),
  não base pura. Para `max-attempts=8 + multiplicador=3.0 + jitter=0.5`, o último backoff é ~2187s ×
  1.5 ≈ 3280s (~55 min), não ~36 min como estimado inicialmente pelo reviewer de performance.

## Plano de ação sugerido (antes do merge)

1. Fortalecer `validarParametros` e o construtor do record com `Double.isFinite` + validar
   `backoffInicialSegundos >= 1` (high, poucas linhas + 3-4 testes RED).
2. Remover ou documentar+testar o `catch (IllegalStateException) isRetryExhausted` como dead code
   (medium, RED primeiro padronizando os 2 testes em `GatewayRetryPolicy.criar`).
3. Padronizar os 2 testes dissidentes em `GatewayRetryPolicy.criar` (low, mecânico).
4. Corrigir os 2 `@DisplayName` "Nao deve..." → "Deve nao..." (low, cosmético).
5. Adicionar teste direto para status 408 (low, espelhar o de 429).
6. Adicionar `metodo` ao log DEBUG de retry (low, mover o log para `bloquearComRetry`).

Acompanhamento:

- Adicionar propriedade `max-backoff-segundos` (medium, cinto-e-suspensório operacional).
- Documentar a janela de lock transacional estendida como item de observabilidade rastreado
  (medium, candidate a entregável separado já listado no PRD como Out of Scope).
