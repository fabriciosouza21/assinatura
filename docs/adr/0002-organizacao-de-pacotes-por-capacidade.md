# ADR 0002: Organização de pacotes por capacidade de negócio (Common Closure)

**Date:** 2026-08-01
**Status:** Proposed
**Contexto:** Desafio técnico Globo — Sistema de Assinaturas

## Context

O desafio define **três** capacidades de negócio, e é por elas que o sistema vai
mudar ao longo do tempo:

| # | Requisito | Onde vive hoje |
|---|-----------|----------------|
| 1 | Cadastrar usuário e criar assinatura (uma ativa por vez) | `usuario/`, `user/`, `assinatura/` |
| 2 | Renovação automática no vencimento, suspensão após 3 tentativas | `assinatura/`, `renovacao/`, `pagamento/renovacao/` |
| 3 | Cancelamento com acesso preservado até o fim do ciclo | **nenhum código** — só `docs/cancelamento/*.puml` e `docs/openapi/cancelamento-assinatura.yaml` |

A organização atual é *feature folder*, mas as pastas foram nomeadas por
**substantivo do modelo** (`assinatura`, `cobranca`, `webhook`) e não por
**motivo de mudança**. E, dentro de cada pasta, os arquivos são despejados sem
nenhuma estrutura. O resultado é que uma pasta concentra várias capacidades
enquanto outras ficam anêmicas — e as grandes viram uma lista de 13 a 19 nomes
sem hierarquia.

### Inventário atual (`main`, sem testes)

```
com.globo.assinatura              com.globo.pagamento
  assinatura/    19 arquivos        webhook/     13
  usuario/        8                 cobranca/    10
  messaging/      7 (4 em event/)   messaging/    9 (4 em event/)
  auth/           5                 gateway/      8
  outbox/         5                 renovacao/    7
  security/       3                 security/     1
  pagamento/      2
  user/           2
  web/            2
  renovacao/      1
```

### Problemas concretos

1. **`assinatura/` é um balde.** 19 arquivos, cinco motivos de mudança: o
   agregado (`Assinatura`, `StatusAssinatura`, `Plano`), a adesão
   (`SolicitarAssinatura`, `AssinaturaRequest`, `AssinaturaAbertaException`), a
   consulta (`ConsultarAssinatura`, `AssinaturaResponse`), o consumo de pagamento
   (`ProcessarPagamento`) e o **domínio de renovação** (`Renovacao`,
   `RenovacaoRepository`, `StatusRenovacao`). Nada disso muda junto.

2. **A renovação está partida em duas pastas.** `renovacao/` tem **um** arquivo
   (`RenovacaoScheduler`), e o agregado que ele manipula está em `assinatura/`.
   Mexer no requisito 2 obriga a abrir duas pastas — o oposto de Common Closure.
   O mesmo vale para o consumo de pagamento, espalhado por `assinatura/`
   (`ProcessarPagamento`), `pagamento/` (as duas classes de idempotência) e
   `messaging/` (`PagamentoStatusConsumer`).

3. **Dependência circular entre pacotes** (viola o Acyclic Dependencies
   Principle): `outbox/OutboxPublisher.java:3` importa
   `messaging.RotasEventoTopicoProperties`, e `messaging/MessagingConfig.java:3`
   importa `outbox.RetryPolicy`. Não há como compilar um sem o outro.

4. **`messaging/` mistura três coisas.** Configuração técnica do Kafka
   (`MessagingConfig`, `RotasEventoTopicoProperties`), o **contrato de eventos**
   entre os serviços (`event/*`, duplicado literalmente nos dois serviços) e
   consumers que são código de capacidade (`PagamentoStatusConsumer` pertence à
   adesão, `RenovacaoSolicitadaConsumer` à renovação).

5. **`user/` vs `usuario/`.** Não é duplicação acidental: `Usuario` (tabela
   `usuarios`) é o perfil de negócio e `User` (tabela `users`) é a credencial,
   ligados por `usuario_id`. Mas o par EN/PT sugere duplicidade, e
   `UsuarioService.cadastrar()` grava os dois na mesma transação — ou seja,
   mudam juntos e estão separados.

6. **Quatro `@RestControllerAdvice` sem escopo declarado**
   (`assinatura/`, `usuario/`, `auth/`, `web/`). O Spring aplica todos
   globalmente, então a posição na feature folder é decorativa e a precedência
   entre eles é implícita.

7. **Infra dentro de feature.** `assinatura/ClockConfig` é um `@Configuration`
   transversal (usado por adesão e renovação) morando na pasta de uma capacidade.

8. **`Plano` duplicado** em `assinatura/assinatura/` e `pagamento/cobranca/`, sem
   registro de que é cópia deliberada de contrato.

### O teste decisivo: onde entra o cancelamento?

O requisito 3 já tem contrato fechado (`.puml` + OpenAPI) e nenhum código. Pela
estrutura atual ele adicionaria ~6 arquivos (controller, command, response,
exceção, handler, evento) **dentro do `assinatura/` que já tem 19** — levando a
pasta a 25 arquivos e a seis motivos de mudança. É a evidência de que a
organização atual não escala para a próxima entrega.

## Decision

Duas decisões encaixadas: **como os pacotes se dividem** (macro) e **como cada
pacote se organiza por dentro** (micro).

### 1. Macro — três zonas e uma regra de dependência

| Zona | O que é | Exemplos |
|------|---------|----------|
| **Capacidade** | Um requisito de negócio ponta a ponta: fronteiras + caso de uso + regras próprias | `adesao/`, `renovacao/`, `cancelamento/`, `cadastro/` |
| **Núcleo** | O agregado compartilhado por várias capacidades. Puro domínio | `assinatura/`, `usuario/`, `cobranca/` |
| **`shared/`** | Plumbing técnico, **zero regra de negócio** | `shared/outbox/`, `shared/kafka/`, `shared/web/` |

**Regra única, verificável:** `capacidade → núcleo → shared`. Nunca o inverso, e
**nunca capacidade → capacidade**. Se duas capacidades precisam do mesmo código,
ele desce para o núcleo ou para `shared/`.

### 2. Micro — a anatomia interna de um pacote de capacidade

Aqui está a correção do que faltava: capacidade não é lista de arquivos, é
**miolo de negócio cercado por fronteiras nomeadas**.

> **A raiz do pacote é o negócio. Os subpacotes são as fronteiras técnicas.**

Vocabulário fechado — quatro nomes, sempre os mesmos, nos dois serviços:

| Slot | Contém | Muda quando… |
|------|--------|--------------|
| **(raiz)** | Casos de uso (commands/queries), entidades e regras próprias da capacidade, exceções de negócio | …a **regra** muda |
| **`api/`** | Controller, request/response, `@RestControllerAdvice` da capacidade | …o **contrato REST** muda |
| **`evento/`** | Consumers e publishers Kafka da capacidade | …o **contrato de mensageria** muda |
| **`agendador/`** | `@Scheduled` que dispara a capacidade | …a **política de disparo** muda |
| **`idempotencia/`** | Tabela de deduplicação e seu repository | …a **estratégia de dedup** muda |

Cada capacidade usa **só os slots que tem**. Nenhuma pasta vazia. E a lista de
slots presentes já conta a história: `renovacao/` sem `api/` é, de saída, uma
capacidade sem superfície REST; `webhook/` sem `agendador/` é reativa pura.

Duas consequências que valem por si:

- Abrir `adesao/` mostra primeiro `SolicitarAssinatura` e
  `ConfirmarPagamentoAdesao` — os **verbos do negócio**, não `AssinaturaRequest`
  em ordem alfabética.
- `webhook/`, o pior pacote hoje com 13 arquivos, vira raiz com 4, `api/` com 8
  e `idempotencia/` com 2.

**O núcleo é plano, sem slots.** Ele não tem fronteiras — é só domínio. Se um
pacote de núcleo pedir um `api/`, é sinal de que virou capacidade e deve ser
extraído (foi o que aconteceu com `usuario/`, ver abaixo).

**`shared/` é plano dentro de cada subpacote.** `shared/outbox/`, `shared/kafka/`
etc. são pequenos e técnicos; slot ali seria cerimônia.

### Alvo — `com.globo.assinatura`

```
AssinaturaApplication

assinatura/                       NÚCLEO — o agregado e seu ciclo de vida (plano)
  Assinatura                      ativar, recusar, renovar, suspender, cancelar
  AssinaturaRepository
  StatusAssinatura
  Plano
  AssinaturaNaoEncontradaException

adesao/                           REQ 1 (escrita) — criar assinatura, cobrar o 1º ciclo
  SolicitarAssinatura
  ConfirmarPagamentoAdesao        ← era assinatura/ProcessarPagamento
  AssinaturaAbertaException
  UsuarioNaoEncontradoException
  api/
    AdesaoController              POST /assinaturas
    AssinaturaRequest
    AssinaturaCriadaResponse
    AdesaoExceptionHandler
  evento/
    PagamentoAdesaoConsumer       ← era messaging/PagamentoStatusConsumer
  idempotencia/
    PagamentoEventoProcessado     ← era o pacote pagamento/
    PagamentoEventoProcessadoRepository

consulta/                         REQ 1 (leitura) — read model, ponto do cache Redis
  ConsultarAssinatura
  api/
    ConsultaAssinaturaController  GET /assinaturas/{uuid}
    AssinaturaResponse

renovacao/                        REQ 2 — renovação automática e suspensão
  Renovacao                       ← era assinatura/
  RenovacaoRepository             ←    "
  StatusRenovacao                 ←    "
  SolicitarRenovacao              extraído do scheduler
  ProcessarRenovacaoResultado     ← chega pela branch prd-be10
  agendador/
    RenovacaoScheduler            varredura de vencimentos
  evento/
    RenovacaoResultadoConsumer    ← prd-be10
  idempotencia/
    RenovacaoEventoProcessado(+Repository)   ← prd-be10

cancelamento/                     REQ 3 — a construir, hoje só contrato
  CancelarAssinatura              opt-out ou cancelamento imediato, por status
  api/
    CancelamentoController        POST /assinaturas/{uuid}/cancelamento
    CancelamentoResponse
    CancelamentoExceptionHandler

usuario/                          NÚCLEO — identidade do cliente (plano)
  Usuario, UsuarioRepository
  Credencial, CredencialRepository     ← eram user/User, user/UserRepository

cadastro/                         REQ 1 — cadastrar usuário
  CadastrarUsuario                     ← era usuario/UsuarioService
  EmailJaCadastradoException
  api/
    UsuarioController, UsuarioRequest, UsuarioResponse
    EmailJaCadastradoExceptionHandler

auth/                             login e emissão de JWT
  AutenticarUsuario                    ← era auth/AuthService
  api/
    AuthController, LoginRequest, LoginResponse
    BadCredentialsExceptionHandler

shared/
  contrato/  AssinaturaSolicitada, RenovacaoSolicitada, PagamentoStatusAtualizado,
             StatusPagamento, CancelamentoAgendado*, AssinaturaCancelada*,
             AssinaturaRenovada*, RenovacaoTentativasEsgotadas*
  kafka/     KafkaConfig, RotasEventoTopicoProperties, EventoInvalidoException
  outbox/    OutboxEvent, OutboxRepository, OutboxStatus, OutboxPublisher, RetryPolicy
  seguranca/ SecurityConfig, JwtService, JwtAuthenticationFilter
  web/       Problem, ProblemExceptionHandler
  tempo/     ClockConfig
```

`*` = eventos ainda a criar. `shared/contrato/` casa com o nome já usado em
`docs/contratos/contrato-eventos-kafka.puml`.

### Alvo — `com.globo.pagamento`

```
PagamentoApplication

cobranca/                         NÚCLEO — o agregado de cobrança (plano)
  Cobranca, CobrancaRepository, StatusCobranca, Plano
  CobrancaNaoEncontradaException

adesao/                           cobrança do 1º ciclo
  CriarCobrancaAdesao             ← era cobranca/CriarCobrancaService
  evento/
    AssinaturaSolicitadaConsumer  ← era messaging/

consulta/                         consulta de cobrança
  ConsultarCobranca
  api/
    CobrancaController, CobrancaResponse, CobrancaExceptionHandler

renovacao/                        REQ 2 — as 3 tentativas
  PagamentoRenovacao(+Repository)
  TentativaCobranca(+Repository)  regra das 3 tentativas — é domínio, fica na raiz
  StatusTentativa
  CriarPagamentoRenovacao
  DecidirResultadoRenovacao*      ← branch prd-be13
  agendador/
    CobrancaRenovacaoScheduler
  evento/
    RenovacaoSolicitadaConsumer   ← era messaging/
    PublicarResultadoRenovacao*   ← prd-be13

cancelamento/                     REQ 3 — a construir
  CancelarTentativasPendentes
  evento/
    CancelamentoConsumer          CancelamentoAgendado / AssinaturaCancelada

webhook/                          callback do gateway  (13 → 4 + 8 + 2)
  ProcessarWebhookPagamento
  NormalizadorStatus
  WebhookInvalidoException, PublicacaoIndisponivelException
  api/
    WebhookPagamentoController, WebhookEvent, WebhookData, WebhookAck, Erro
    WebhookExceptionHandler
    HmacSignatureValidator, WebhookConfig    autenticação é parte do contrato
  idempotencia/
    WebhookEventoProcessado(+Repository)

gateway/                          adapter de saída — ACL do meio de pagamento (plano)
  GatewayPagamentoClient, GatewayWebClientConfig
  CreatePaymentRequest, CreatePaymentResponse, PaymentResponse
  CobrancaCriada, StatusGateway, CobrancaGatewayIndisponivelException

shared/
  contrato/, kafka/, seguranca/, web/
```

`gateway/` fica no topo, e não em `shared/`, porque é um **adapter de saída com
identidade própria**: muda quando o contrato do provedor muda, não quando muda a
infraestrutura interna. Internamente é plano — não é capacidade, não tem slots.

### Decisões de detalhe

1. **Consumers moram na capacidade, em `evento/`.** Um consumer é fronteira de
   entrada de um caso de uso; muda quando o caso de uso muda. Em `shared/kafka/`
   fica só o genérico: factory, error handler, DLQ, tópicos.

2. **A tabela de idempotência mora com quem deduplica**, em `idempotencia/`. São
   três hoje (`PagamentoEventoProcessado`, `WebhookEventoProcessado`,
   `RenovacaoEventoProcessado`), cada uma numa capacidade diferente.

3. **`idempotencia/` é só dedup técnico.** Entidade que carrega regra
   (`TentativaCobranca`, `Renovacao`) é domínio e fica na raiz da capacidade.

4. **`@RestControllerAdvice` escopado.** Cada `api/` mantém o seu handler,
   declarado com `@RestControllerAdvice(assignableTypes = XController.class)`,
   tornando o escopo explícito em vez de global-por-acidente. Só
   `shared/web/ProblemExceptionHandler` permanece global (validação e fallback).

5. **Quebra do ciclo `outbox ↔ messaging`.** `RotasEventoTopicoProperties` desce
   para `shared/kafka/` e `RetryPolicy` fica em `shared/outbox/`; o bean de
   `RetryPolicy` sai de `MessagingConfig` e passa a ser declarado no próprio
   `shared/outbox/`. Aresta única: `outbox → kafka`.

6. **`usuario/` se parte em núcleo + capacidade.** `Usuario` e `Credencial` são
   o agregado de identidade (núcleo, lido por `auth/`); `cadastro/` é a
   capacidade do requisito 1. Renomear `User`→`Credencial` elimina o par EN/PT
   que sugere duplicidade. A tabela continua `users` — renomear é migration
   separada e opcional.

7. **`Plano` duplicado vira cópia declarada.** Junto com `shared/contrato/*`, é
   contrato entre serviços sem biblioteca compartilhada. Registrar a escolha
   (autonomia de deploy > DRY entre serviços) no Javadoc dos dois lados.

8. **Um controller por capacidade sobre `/assinaturas`.** POST em
   `adesao/api/`, GET em `consulta/api/`, POST `/{uuid}/cancelamento` em
   `cancelamento/api/`. É consequência direta do CQRS Lite já adotado
   (`AGENTS.md`) e mantém o cache de leitura isolado da escrita.

9. **Os testes espelham a mesma anatomia.** `adesao/SolicitarAssinaturaTest`,
   `adesao/api/AdesaoControllerTest`, `adesao/evento/PagamentoAdesaoConsumerTest`.
   O `@Tag("integration")` continua marcando o corte de execução, não o pacote.

### O que substitui o `package-private`

Subpacote em Java não herda visibilidade: com `api/` chamando a raiz, os casos
de uso precisam ser `public`. Trocamos encapsulamento do compilador por regra
verificada em teste. Quatro regras ArchUnit cobrem o desenho inteiro:

```java
// 1. Zonas: a seta só aponta para dentro
noClasses().that().resideInAPackage("..shared..")
    .should().dependOnClassesThat().resideInAnyPackage("..adesao..", "..renovacao..", ...);

// 2. Capacidades não se enxergam
slices().matching("com.globo.assinatura.(*)..").should().notDependOnEachOther();

// 3. A fronteira não vaza para dentro: nada na raiz importa do próprio api/
noClasses().that().resideOutsideOfPackage("..api..")
    .should().dependOnClassesThat().areAnnotatedWith(RestController.class);

// 4. Sem ciclos entre pacotes  (pega a regressão do outbox ↔ kafka)
slices().matching("com.globo.assinatura.(**)").should().beFreeOfCycles();
```

São ~40 linhas num `ArquiteturaTest`, rodam no `make test` e sustentam a
estrutura depois que o refactor acabar — que é a diferença entre um desenho
documentado e um desenho que se mantém.

### Validação: o cancelamento pela estrutura nova

Implementar o requisito 3 passa a ser:

- `assinatura/cancelamento/` — `CancelarAssinatura` + `api/` com 3 arquivos;
- `pagamento/cancelamento/` — `CancelarTentativasPendentes` + `evento/` com 1;
- dois métodos no núcleo: `Assinatura.agendarCancelamento()` (já existe
  `cancelar()`);
- dois records em `shared/contrato/` + as rotas correspondentes;
- uma migration em cada serviço.

Nenhum arquivo de `adesao/`, `consulta/` ou `renovacao/` é tocado — e o diff da
PR é lido de cima a baixo como o `.puml` do cancelamento. É exatamente o que o
Common Closure Principle promete.

## Consequences

**A favor**

- Cada requisito do desafio tem um diretório, e cada diretório abre mostrando os
  verbos do negócio. A leitura do código bate com a leitura do PDF.
- Nenhum pacote passa de ~8 arquivos num nível. O de 19 vira 5; o de 13 vira 4.
- Os slots presentes documentam a superfície da capacidade sem abrir arquivo.
- A regra `capacidade → núcleo → shared` é executável (ArchUnit), não folclore.
- Trabalho paralelo em worktrees colide menos: hoje quase toda branch toca
  `assinatura/`.
- Espaço natural para os diferenciais: cache Redis em `consulta/`, tracing em
  `shared/`.

**Contra**

- **Perde-se o `package-private`** entre a raiz e os slots; casos de uso viram
  `public`. Compensado pelas regras ArchUnit acima — troca consciente.
- `com.globo.assinatura.assinatura` mantém o "stutter". Aceito: o pacote é o
  agregado, e renomear para `dominio/` seria voltar a nomear por camada.
- Três controllers sobre a mesma rota base custa um pulo a mais para quem
  procura "o controller de assinatura". Mitigado pelos nomes
  (`AdesaoController`, `ConsultaAssinaturaController`, `CancelamentoController`).
- Capacidades pequenas (`pagamento/adesao/`: 2 arquivos em 2 níveis) ficam com
  aparência de over-engineering. Aceito em nome da anatomia uniforme — o preço
  de prever onde o próximo arquivo entra.
- `shared/` pode virar o novo balde. Contramedida: nada em `shared/` referencia
  tipo de negócio — regra 1 do ArchUnit.
- O refactor mexe em ~100 arquivos entre main e test (só pacote e imports).

## Plano de migração

O estado das branches favorece fazer agora:

```
$ git branch --no-merged develop
  docs/renovacao-0.3.0                        +1  (só docs)
  feat/prd-be10-consome-resultado-renovacao  +19  ← única com código relevante
  feat/prd-be13-webhook-renovacao             +1  (só PRD)
  worktree-feat-outbox-dlq-recovery           +2  (só shared/outbox)
```

Sete das dez worktrees já estão contidas em `develop` — podem ser removidas antes
do refactor. O risco real de conflito se resume a **duas** branches.

Sequência sugerida, em `refactor/pacotes-por-capacidade` a partir de `develop`:

1. **Fechar a fila.** Mergear `prd-be10` em `develop` **antes** do refactor (é a
   que traz `ProcessarRenovacaoResultado` e 4 eventos novos). Remover as
   worktrees já mergeadas: `git worktree remove .worktrees/<nome>`.
2. **`shared/` primeiro** — move `outbox`, `messaging`→`kafka`+`contrato`,
   `security`→`seguranca`, `web`, `ClockConfig`→`tempo`. Quebra o ciclo do item
   5. É o passo que desbloqueia `worktree-feat-outbox-dlq-recovery`.
3. **Núcleo** — enxugar `assinatura/` para os 5 arquivos do agregado; fundir
   `user/` em `usuario/` como `Credencial`.
4. **Capacidades, uma por commit** — `adesao/`, `consulta/`, `renovacao/`,
   `cadastro/`, `auth/`, já nascendo com os slots. Mover consumers para
   `evento/` e tabelas de dedup para `idempotencia/`.
5. **Espelhar em `pagamento/`**, mesma ordem.
6. **`ArquiteturaTest`** com as quatro regras — a partir daqui o desenho se
   defende sozinho.
7. **Testes** acompanham cada passo no mesmo commit.
8. **`cancelamento/`** entra depois, já na estrutura nova — é a validação prática
   do desenho.

Cada passo é um commit `refactor:` isolado, com `git mv` (preserva histórico com
`git log --follow`) e `make verify` verde antes de seguir. Nenhum passo altera
comportamento: só `package`, `import` e nomes de classe.

**Coordenação de migrations:** o refactor não cria migration nenhuma. As
próximas livres são `V9` em `assinatura` (`V10` se `prd-be10` entrar antes, que
já usa `V9`) e `V6` em `pagamento`.
