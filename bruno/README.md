# Collection Bruno

Collection no formato [OpenCollection YAML](https://docs.usebruno.com/opencollection-yaml/overview)
do [Bruno](https://docs.usebruno.com/). Git-friendly, em texto plano.

## Como usar

1. Suba o ambiente: na raiz do repo, `docker compose up -d --build`.
2. No Bruno: **Open Collection** e selecione a pasta `bruno/`.
3. Selecione o environment **Local** (no canto superior direito).

As variáveis de ambiente apontam para as portas do compose:
`assinaturaUrl` (18080), `pagamentoUrl` (18082), `mockGatewayUrl` (8081).

## Estrutura

```
bruno/
├── api/                  # endpoints, organizados por serviço
│   ├── assinatura/       #   Assinatura Service (usuarios, auth, assinaturas)
│   ├── pagamento/        #   Pagamento Service (cobranças)
│   ├── mock-gateway/     #   mock do gateway de pagamento
│   └── renovacao/        #   consultas do fluxo de renovação
├── fluxo/                # fluxos encadeados, prontos para rodar em ordem
│   ├── assinatura/       #   adesão ponta a ponta (cadastro → ATIVA)
│   └── renovacao/        #   renovação (decisão manual no mock)
├── environments/         # environments (Local)
├── opencollection.yml
└── README.md
```

As pastas de `api/` são blocos de construção reutilizáveis, um request por
arquivo. As pastas de `fluxo/` reproduzem um caminho completo encadeando esses
requests via variáveis capturadas no `after-response`.

> **Credenciais de teste**: o environment Local já vem com `password: admin123`
> (variável marcada como `secret`). Essa senha é o seed de desenvolvimento
> definido em `services/assinatura/src/main/resources/db/migration/V1__init.sql`
> e foi versionada **intencionalmente** para facilitar os testes da collection.
> A mesma senha é reaproveitada no cadastro do cliente de teste. Não há
> credenciais reais ou de produção envolvidas.

## Fluxo sugerido (assinatura)

1. **Cadastrar usuario** → cria o cliente de teste e captura `usuarioId`
   automaticamente (`after-response`).
2. **Login** → autentica **como o cliente cadastrado** e captura `token`.
3. **Solicitar assinatura** → envia `Bearer {{token}}` e apenas o `plano`;
   devolve a assinatura com status `AGUARDANDO_PAGAMENTO` e captura
   `assinaturaId`.
4. **Consultar assinatura ativa** → `GET /assinaturas/ativa`, também com
   `Bearer {{token}}`: devolve a assinatura ATIVA do dono do token, sem precisar
   do `assinaturaId`. Sem assinatura ativa responde `404`; o `admin` do seed
   recebe `403`.

> As rotas de assinatura exigem JWT (ADR 0003): sem o passo de **Login** elas
> respondem `401`. O dono vem do token, então `usuarioId` não é mais enviado no
> corpo da solicitação. O `admin` do seed não serve para assinar — não tem
> usuário de domínio ligado e recebe `403` no `POST /assinaturas`.

> Os planos aceitos são `BASICO`, `PREMIUM` e `FAMILIA`. O status parte de
> `AGUARDANDO_PAGAMENTO` e migra para `ATIVA` ou `PAGAMENTO_RECUSADO` conforme
> o processamento do pagamento.

## Fluxo sugerido (mock gateway)

1. **Criar pagamento** → captura `paymentId` automaticamente (`after-response`).
2. **Consultar pagamento** → usa o `paymentId` capturado (status `PENDING`).
3. **Simular aprovação** → força `APPROVED` e dispara o webhook para o serviço
   de pagamento.

## Observabilidade (metricas)

Os dois serviços expõem `GET /actuator/prometheus` no formato do Prometheus,
sem autenticação (`api/assinatura/metricas-prometheus` e
`api/pagamento/metricas-prometheus`).

- A métrica **`outbox_eventos`** conta os eventos da outbox por status, com as
  tags `servico` (`assinatura` | `pagamento`) e `status` (`pendente` |
  `retentativa_dlq` | `falha`), atualizada a cada
  `APP_OUTBOX_METRICAS_INTERVALO_MS` (default 60s). O gauge de `falha` é o
  sinal de evento que esgotou as tentativas e ficou terminal.
- Junto vêm as métricas de JVM e do Kafka (`jvm_memory_*`,
  `kafka_producer_*`), além das customizadas como
  `pagamento_gateway_retry_tentativas`.

Para ver o gauge refletir eventos reais: solicite uma assinatura e, com o
Kafka parado (`docker compose stop kafka`), o evento fica `pendente` e passa a
`falha` após esgotar as tentativas do publisher.

## Fluxo encadeado completo (assinatura)

Ponta a ponta, do cadastro do usuário até a assinatura `ATIVA`, sem editar
variáveis manualmente entre os passos. Cada request captura automaticamente o
id do passo seguinte via `after-response`.

> A pasta `bruno/fluxo/assinatura/` já reproduz este fluxo pronto para rodar,
> em ordem: são os mesmos requests das pastas de endpoint, com os ids de
> sequência ajustados para o encadeamento. As pastas de `api/` (`assinatura/`,
> `pagamento/`, `mock-gateway/` e `renovacao/`) continuam organizadas por
> serviço, como blocos de construção reutilizáveis.

1. **Cadastrar usuário** → captura `usuarioId`.
2. **Login** → autentica como o cliente cadastrado e captura `token`.
3. **Solicitar assinatura** → envia `Bearer {{token}}` e o `plano`; devolve a
   assinatura com status `AGUARDANDO_PAGAMENTO` e captura `assinaturaId`.
4. **Consultar cobrança** (`api/pagamento/consultar-cobranca`) → usa o
   `assinaturaId` capturado e devolve o `paymentId` gerado no gateway; captura
   `paymentId`.
5. **Simular aprovação** (`api/mock-gateway/simular-aprovacao`) → usa o
   `paymentId` capturado, força `APPROVED` e dispara o webhook que leva a
   assinatura a `ATIVA`.
6. **Consultar assinatura ativa** → usa o mesmo token para confirmar o status
   `ATIVA` no `GET /assinaturas/ativa`, sem depender do `assinaturaId`.

> O passo **Consultar cobrança** é a ponte entre os dois serviços: sem ele, o
> `paymentId` fica preso no Pagamento Service e não há como aprovar a cobrança
> no mock. O `assinaturaId` é o uuid público da assinatura, o mesmo valor usado
> como `Idempotency-Key` e `externalReference` no fluxo do mock.

## Fluxo de renovação automática

O ciclo de renovação é disparado pelo Assinatura Service quando
`proxima_renovacao_em` vence, e a cobrança é decidida no mock, como na adesão.
O teste gira em torno de duas variáveis:

- `APP_RENOVACAO_CICLO_MS`: duração do ciclo de renovação (default 1 minuto;
  produção usa 30 dias). Com valor **menor que um dia**, a janela `ATIVA` dura
  exatamente `cicloMs` reais e a renovação dispara logo após vencer.
- `APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS`: espera entre tentativas de cobrança
  recusadas (default `1,3`). Com `0,0`, as recusas são imediatas e o esgotamento
  de tentativas acontece em segundos.

### Perfil de teste rápido

No `.env` (a partir de `.env.example`), acelere os schedulers e o ciclo:

```
APP_RENOVACAO_CICLO_MS=60000
APP_RENOVACAO_INTERVALO_MS=1000
APP_RENOVACAO_SCHEDULER_INTERVALO_MS=1000
APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS=0,0
APP_OUTBOX_INTERVALO_MS=1000
```

> O `proxima_renovacao_em` é um **instante** (timestamptz): com ciclo menor que
> um dia a janela `ATIVA` dura exatamente `cicloMs` reais e a renovação dispara
> no sweep seguinte (~1s). Aprovada, a assinatura volta a `ATIVA` com o instante
> avançado em mais um ciclo: aprovar → conferir `ATIVA` estável → aguardar
> `EM_RENOVACAO` no ciclo seguinte. Ciclos de um dia ou mais aguardam dias
> reais.

## Gotchas

- **`make test-integration` derruba a stack inteira, não só o Postgres.** Os
  targets `test-integration-clean`/`up` rodam `docker compose -f
  ../../docker-compose.yml down -v --remove-orphans` — o mesmo projeto compose
  da raiz, então `down -v` derruba `assinatura`, `pagamento`, `kafka`,
  `mock-pagamento` e `jaeger` e apaga o volume do Postgres. Não rode os testes
  de integração numa terminal ao lado de uma stack em uso para testes manuais;
  depois, suba tudo de novo com `docker compose up -d --build` na raiz.
- **Erros de `Unable to rollback against JDBC Connection` logo após `docker
  compose up` são esperados e inofensivos**: um tick agendado do publisher da
  outbox cai no meio do restart, enquanto o Postgres ainda sobe. Somem em
  ~20s e o fluxo E2E não é afetado; só se preocupar se continuarem depois do
  healthcheck ficar verde.

### Fluxo de aprovação

Partindo do **fluxo encadeado completo** (assinatura `ATIVA`, ciclo 1 em
andamento). A pasta `bruno/fluxo/renovacao/` já traz os requests em ordem para
este caminho:

1. Aguarde a renovação disparar (~2s com o perfil rápido): a assinatura fica
   `EM_RENOVACAO` até a decisão da cobrança.
2. **Consultar renovação** (`fluxo/renovacao/consultar-renovacao`) → usa o
   `assinaturaId` capturado e devolve o `paymentId` da cobrança de renovação;
   captura `paymentId` e `renovacaoId`.
3. **Simular aprovação** (`fluxo/renovacao/simular-aprovacao`) → força
   `APPROVED` no payment da renovação; o webhook aprova a tentativa e a
   assinatura volta a `ATIVA` com o próximo instante agendado.
4. **Consultar assinatura** (`fluxo/renovacao/consultar-assinatura`) → status
   `ATIVA` estável por um ciclo (`proximaRenovacaoEm` avançada).
5. Aguarde o ciclo vencer (~60s com o perfil rápido) → `EM_RENOVACAO` de novo:
   repetir os passos 2-4 mantém o ciclo rodando.

> `consultar-renovacao` responde `404` enquanto a renovação não disparou: é o
> sinal de que você chegou cedo demais. Após aprovar, um novo `renovacaoId`
> aparece no passo 2: cada ciclo é uma renovação distinta.
>
> O `api/assinatura/consultar-assinatura-ativa` do fluxo de adesão exige
> `ATIVA` e serve para validar a ativação; o
> `fluxo/renovacao/consultar-assinatura` aceita também `EM_RENOVACAO` e
> `SUSPENSA`, porque o ciclo fica `EM_RENOVACAO` entre o disparo e a decisão da
> cobrança.

### Fluxo de esgotamento (suspensão)

Com `APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS=0,0` (3 tentativas em sequência
rápida), o mesmo caminho com recusas suspende a assinatura:

1. Aguarde a renovação disparar.
2. **Consultar renovação** (`fluxo/renovacao/consultar-renovacao`) → captura o
   `paymentId` da tentativa corrente.
3. **Simular recusa** (`fluxo/renovacao/simular-recusa`) → `REJECTED`; o
   webhook agenda a próxima tentativa imediatamente.
4. Repita os passos 2-3 até a terceira recusa — cada ciclo gera um novo
   `paymentId`, e o mock só aceita a transição `PENDING → REJECTED`.
5. **Consultar assinatura** (`fluxo/renovacao/consultar-assinatura`) → status
   `SUSPENSA`.
