# PRD: Retentativa com backoff no cliente do gateway

**Data:** 2026-08-03
**Status:** Draft
**Roadmap de origem:** `docs/roadmap/2026-08-02-desafio-entrega-consolidada.md` (BE-21)
**Serviço:** Pagamento Service (`services/pagamento/`)
**Versão alvo:** `0.5.0`

## Problem

Quando o gateway de pagamento sofre uma falha transitória, um timeout ou um
erro 5xx, essa falha chega imediatamente ao domínio como uma falha técnica. Com
o teto de três falhas técnicas consecutivas introduzido pelo BE-20, três
tremores curtos do gateway em três ticks do scheduler bastam para suspender uma
assinatura que não tem nada de errado. Hoje não existe nenhuma camada de retry
no cliente HTTP: a primeira resposta ruim já vira exceção e já conta contra o
teto.

A review do BE-20 (`docs/reviews/feat-teto-falhas-tecnicas-gateway.md`) flaggeou
expressamente isso como o item a ser resolvido pelo BE-21. Quem sente a dor é o
usuário final, cuja assinatura é suspensa por instabilidade de um terceiro, e a
operação, que abre chamados para o que é, no fundo, ruído de infraestrutura.

## Background

O Pagamento Service fala com o gateway por um `WebClient` (Spring WebFlux sobre
Reactor Netty) encapsulado em `GatewayPagamentoClient`. Há três chamadas ao
gateway: criação de cobrança de adesão, criação de cobrança de renovação
(disparada pelo scheduler de renovação) e consulta de status de pagamento
(disparada pelo webhook). Nenhuma dessas três chamadas faz retry hoje. Os
timeouts (2s de conexão, 10s de resposta) são hardcoded na factory do WebClient
e não são externalizáveis.

O scheduler de renovação varre até 200 tentativas por tick e executa a chamada
ao gateway dentro de uma transação que segura o lock da tentativa. Sem retry,
uma falha transitória vira exceção imediata; com retry, a mesma chamada pode se
recuperar antes de atingir o domínio. O escopo deste PRD é o cliente do gateway.
A janela de lock da transação do scheduler é um risco correlato, mas de escopo
distinto, tratado adiante em "Out of Scope".

O BE-20 estabeleceu o contrato: cada falha técnica contada contra o teto
corresponde a uma decisão de negócio tomada pelo scheduler, não a cada tentativa
HTTP individual. O retry do BE-21 esgota dentro de uma única chamada ao gateway;
se mesmo assim falhar, propaga uma `CobrancaGatewayIndisponivelException` e o
scheduler registra exatamente uma falha técnica. O contador e o teto do BE-20
não mudam.

## Requirements

### Must Have

- O cliente do gateway aplica retry com backoff exponencial e jitter sobre falhas
  transitórias (timeout, erros 5xx) antes de propagar a exceção.
- Cobertura uniforme: as três chamadas ao gateway (adesão, renovação e consulta
  de status do webhook) recebem o mesmo comportamento de retry.
- Esgotado o retry, a exceção que chega ao domínio é a mesma de hoje, então o
  teto de falhas técnicas do BE-20 continua contando exatamente uma falha por
  chamada, não uma por tentativa.
- Parâmetros de retry externalizáveis por variável de ambiente
  (`app.gateway.retry.*`: número máximo de tentativas, backoff inicial,
  multiplicador, jitter), sem rebuild.
- Timeouts de conexão e resposta externalizáveis (hoje hardcoded).
- Retry só sobre falhas transitórias. Falhas de negócio (4xx, recusa explícita
  `REJECTED`) não são retried.
- Idempotência preservada: o retry de um POST seguro porque o gateway deduplica
  por `Idempotency-Key`, que já existe hoje.

### Should Have

- Logs estruturados em nível DEBUG por tentativa de retry (evento estável,
  tentativa corrente, backoff aplicado), seguindo a convenção de logging do
  projeto (Fluent API, chave `event` em snake_case).
- Métricas observáveis do retry via instrumentação já presente (OpenTelemetry).

### Out of Scope

- Mitigação estrutural da janela de lock da transação do scheduler. O retry
  síncrono dentro do `@Transactional` agrava o tempo de retenção do lock DB
  durante a chamada ao gateway. Esta é uma preocupação real, flaggeada pela
  review do BE-20, mas de escopo distinto: mover a chamada ao gateway para fora
  da transação, ou espalhar `proximaTentativaEm` em `registrarFalhaTecnica` para
  distribuir re-tentativas entre ticks, é trabalho de arquitetura do scheduler,
  não do cliente do gateway. Deixar registrada no PRD como risco conhecido e
  candidato a entregável separado.
- Circuit breaker, bulkhead e outras políticas de resiliência. O BE-21 entrega
  só retry. Resilience4j ou equivalente fica para quando houver demanda real de
  circuit breaker.
- Modo de outage no mock de pagamento (`docker/mock-pagamento`). O mock é suporte
  de desenvolvimento, não produto. A validação do retry no cliente é coberta por
  testes com MockWebServer, que já suporta enfileirar 500, simular timeout por
  delay e sequências do tipo 500 → 500 → 201.
- Retry sobre falhas de negócio. Recusa explícita do gateway não é transitória.

## Constraints

- Cliente HTTP atual é `WebClient` reativo usado em modo blocking (`.block()`).
  O retry deve usar `Retry.backoff` do Reactor, que já está no classpath, para
  não introduzir dependência nova (sem Spring Retry, sem Resilience4j).
- Java 26, Spring Boot 4.1, Google Java Style com Checkstyle 11.0.1 e Spotless
  (google-java-format 1.30). Javadoc obrigatório em todo tipo e método público.
- Configuração externalizada por variáveis de ambiente, prefixo `app.gateway` no
  `application.yaml` do Pagamento Service.
- Não registrar payloads, credenciais, tokens, PII ou `exception.getMessage()`
  nos logs.
- O `try/catch` do scheduler não muda. A semântica de contagem do BE-20 é o
  contrato inegociável deste PRD.

## Acceptance Criteria

### Retentativa sobre falha transitória

- Dado que o gateway responde 500 na primeira chamada e 201 na segunda, quando o
  cliente chama `criarCobrancaRenovacao`, então o resultado de sucesso é
  retornado ao scheduler sem nenhuma exceção propagada.
- Dado que o gateway responde 503 em todas as tentativas dentro do limite
  configurado, quando o cliente chama, então a exceção
  `CobrancaGatewayIndisponivelException` é propagada ao scheduler.
- Dado que o gateway demora além do timeout de resposta configurado, quando o
  cliente chama, então a chamada é retried com backoff antes de falhar.

### Integração com o teto de falhas técnicas (BE-20)

- Dado que o gateway está indisponível em todas as tentativas de retry, quando o
  scheduler processa um tick, então `registrarFalhaTecnica` é chamado exatamente
  uma vez para aquela tentativa de cobrança, independentemente de quantas
  tentativas HTTP ocorreram dentro do cliente.
- Dado que o gateway se recupera antes de esgotar o retry, quando a cobrança
  succeede, então nenhuma falha técnica é registrada e o contador do BE-20
  permanece zerado.

### Idempotência e segurança do retry

- Dado que o retry reenvia um POST de criação de cobrança, quando o gateway
  recebe a mesma `Idempotency-Key`, então nenhuma cobrança duplicada é criada.
- Dado que o gateway responde 4xx (exceto 408/429) ou `REJECTED`, quando o
  cliente chama, então nenhuma retentativa ocorre.

### Cobertura uniforme

- Dado o mesmo cenário de falha transitória, quando cada um dos três métodos
  (`criarCobranca`, `criarCobrancaRenovacao`, `consultarStatus`) é chamado,
  então os três aplicam retry com backoff antes de propagar.

### Configuração externalizada

- Dado um novo valor para `app.gateway.retry.max-attempts`, quando o serviço
  sobe, então o cliente passa a usar esse valor sem recompilação.
- Dado um novo valor para os timeouts de conexão e resposta, quando o serviço
  sobe, então os timeouts passam a ser esses.

### Jitter e evitar thundering herd

- Dado que múltiplas tentativas de cobrança falham no mesmo tick, quando o retry
  calcula o backoff, então cada chamada espera um intervalo com jitter, de forma
  que as re-tentativas não ocorram todas no mesmo instante.
