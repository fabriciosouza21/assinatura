# Sistema de Assinaturas

Desafio técnico: sistema de gestão de assinaturas para um serviço de streaming.
Usuários assinam planos mensais e a cobrança ocorre automaticamente, com
renovação no vencimento, suspensão após três recusas e cancelamento que mantém o
acesso até o fim do ciclo.

## Contexto

Dois microserviços Spring Boot (Java 26) que conversam via Kafka, mais um mock do
gateway de pagamento para desenvolvimento local.

```
assinatura  ──AssinaturaSolicitada──▶  kafka  ──▶  pagamento  ──▶  gateway (mock)
     ▲                                                              │
     └──────PagamentoStatusAtualizado───── kafka ◀─── webhook ◀─────┘
```

- **`services/assinatura`** cadastra usuários e assinaturas, publica
  `AssinaturaSolicitada` via outbox, consome o resultado de pagamento para
  ativar/suspender, e roda a renovação automática e o cancelamento.
- **`services/pagamento`** consome `AssinaturaSolicitada` e `RenovacaoSolicitada`,
  fala com o gateway, recebe webhooks e publica o status do pagamento via outbox.
- **`docker/mock-pagamento`** é o mock do gateway de pagamento (Go, `net/http`).
  Não é um microserviço, apenas suporte de desenvolvimento.

## Decisões Técnicas

As decisões que sustentam o sistema, agrupadas por tema. Cada uma resume **o quê**
e **por quê**. Detalhe completo nas ADRs (`docs/adr/`) e PRDs (`docs/prd/`).

### Arquitetura e organização

- **Dois serviços por contexto limitado.** Assinatura é dona do contrato e do
  ciclo de vida; Pagamento é dono da cobrança. Conversam só por eventos Kafka,
  nunca direto. Isola regras e libera deploys independentes.
- **Pacotes por capacidade, não por camada (ADR 0002).** Cada requisito do
  desafio num diretório (`adesao/`, `renovacao/`, `cancelamento/`), com três
  zonas (`capacidade → núcleo → shared`) e regra de dependência verificada por
  ArchUnit. Uma mudança toca poucos arquivos e abre mostrando os verbos do
  negócio.
- **CQRS Lite.** Commands de escrita e Queries de leitura no mesmo app e banco.
  Sem Event Sourcing, sem base separada. Suficiente para o domínio, sem custo
  operacional extra.
- **`id` Long interno, `uuid` público.** Joins eficientes no banco; contrato de
  API estável e opaco.

### Concorrência e consistência

- **Uma assinatura aberta por usuário (BE-2, BE-23).** Índice único parcial em
  `(usuario_id) WHERE status IN ('AGUARDANDO_PAGAMENTO','ATIVA','EM_RENOVACAO')`
  + checagem de negócio + `SELECT ... FOR UPDATE`. Defesa em profundidade que
  cobre inclusive a janela de renovação.
- **`FOR UPDATE SKIP LOCKED` nos schedulers e no publisher da outbox.** Múltiplas
  instâncias processam lotes disjuntos sem duplicar trabalho.
- **Renovação por instante preciso (BE-16 a BE-19).** `proxima_renovacao_em` é
  `timestamptz`, varrido com `<= Instant.now(clock)`. Ciclos sub-diários duram o
  tempo exato; atrasos por indisponibilidade são recuperados no sweep seguinte.

### Mensageria e entrega

- **Outbox write-aside nos dois serviços.** O evento é gravado na mesma
  transação do agregado e publicado por polling. Entrega confiável sem
  CDC/Debezium e sem broker transacional.
- **Publisher bloqueante dentro da transação (`send().get()`).** O ack do Kafka é
  conhecido antes do commit. Semântica *at-least-once*, com dedup no consumo.
- **Retry com backoff exponencial + jitter (3 tentativas) na outbox.** Absorve
  tremores curtos do broker sem martelá-lo.
- **`FALHA` recuperável.** O BE-24 promove eventos em `FALHA` de volta ao ciclo
  de publicação após quarentena, com limite de ciclos e `FOR UPDATE SKIP LOCKED`.
- **DLQ de consumer com replay por configuração.** Um `@KafkaListener` dedicado,
  desligado por padrão, republica mensagens dos tópicos `*-dlq` no tópico original
  (BE-27).
- **Idempotência por `event_id` nos consumers.** `ON CONFLICT DO NOTHING` ou
  `existsByEventId`. Uma reentrega do Kafka nunca duplica efeito de negócio.

### Integração com o gateway

- **Webhook é fronteira não confiável (BE-6).** HMAC-SHA256 comparado em tempo
  constante, `eventId` deduplicado e status sempre re-consultado no gateway. O
  callback nunca atualiza estado sozinho.
- **Teto de falhas técnicas do gateway (BE-20).** N falhas técnicas consecutivas
  esgotam a renovação pelo mesmo fluxo das três recusas. Distinto de recusa de
  negócio: o gateway indisponível não pune o usuário imediatamente, mas a
  indisponibilidade persistente suspende.
- **Sem retry no cliente HTTP do gateway hoje (dívida, BE-21).** Apenas timeout
  (2s conexão, 10s resposta). Hoje cada falha técnica conta contra o teto, então
  três tremores curtos suspendem uma assinatura sã. O BE-21 adiciona backoff no
  cliente para conter isso.
- **Mock em Go isolado em `docker/`.** Apenas `net/http`, estado em memória, sem
  persistência. Não é microserviço, é suporte de desenvolvimento.

### Cache e leitura

- **Cache versionado por `INCR` pós-commit, não por `DEL` (BE-15).** A chave de
  leitura inclui a versão; cada escrita incrementa o contador após o commit, e a
  geração anterior vira inalcançável, expirando no TTL. Seguro entre réplicas,
  sem lock distribuído e sem a corrida de repopulação do `DEL`.
- **Degradação silenciosa do Redis.** Falha do cache faz a leitura cair no banco
  e a invalidação ser coberta pelo TTL. O cache nunca derruba a request;
  consistência eventual em segundos.

### Segurança e autenticação

- **JWT obrigatório, dono derivado do token (ADR 0003).** `POST /assinaturas` não
  recebe `usuarioId`: ninguém consegue expressar "assinar por outro". A classe de
  falha é eliminada por construção, não por validação.
- **`401` ≠ `403`.** Token ausente, expirado ou inválido vira `401`; autenticado
  mas não dono do recurso vira `403`. Contrato claro para o cliente.
- **Secrets externalizados em `APP_*`.** O webhook-secret bloqueia o default
  `mock-webhook-secret` fora do perfil dev. Defaults só valem em desenvolvimento
  local.

### Observabilidade e qualidade

- **Logging estruturado.** SLF4J Fluent, chave `event` em `snake_case`, sem
  payload nem PII. Log pesquisável sem vazar dado sensível.
- **OpenTelemetry nos dois serviços.** Correlação de trace entre assinatura,
  Kafka e pagamento.
- **Convenções verificáveis, não folclore.** Checkstyle + Spotless (Google Java
  Style), ArchUnit para a arquitetura de pacotes, pre-commit rodando lint, testes
  unitários e de integração isolados.

### Decisões de domínio

- **Cancelamento preserva o acesso até o fim do ciclo.** `ATIVA` ou
  `EM_RENOVACAO` com renovação automática gera `CancelamentoAgendado` (cancela no
  vencimento); demais status cancelam na hora com `AssinaturaCancelada`.
- **Três recusas suspendem; falha técnica não consome tentativa (BE-10 a BE-13).**
  Backoff em dias (`1,3` = três tentativas em D+0, D+1, D+3). Indisponibilidade do
  gateway é transient e não conta como recusa de negócio.
- **`Plano` → valor num enum no Assinatura Service.** O tipo `Plano` é duplicado
  entre os serviços como cópia declarada de contrato, sem biblioteca compartilhada.
  Autonomia de deploy pesa mais que DRY entre serviços.

## Estado da entrega

Toda regra obrigatória do enunciado está implementada em `develop`: adesão com
pagamento, renovação automática no vencimento, suspensão após três recusas,
teto de falhas técnicas do gateway, assinatura única inclusive durante a
renovação, e cancelamento com acesso até o fim do ciclo.

A íntegra do escopo e do andamento, inclusive o que ainda falta (recuperação
automática da outbox, retry no cliente do gateway, observabilidade de DLQ e
testes com Testcontainers), vive em `docs/roadmap/`, com o documento
`2026-08-02-desafio-entrega-consolidada.md` consolidando o estado de ponta a
ponta.

## Como testar via Bruno

A collection [Bruno](https://docs.usebruno.com/) em `bruno/` cobre os fluxos de
ponta a ponta, encadeando requests sem editar variáveis manualmente. O resumo:

1. Suba o ambiente: `docker compose up -d --build`.
2. No Bruno: **Open Collection** → pasta `bruno/` → environment **Local**.

Fluxos disponíveis (detalhes em `bruno/README.md`):

- **Adesão** (`bruno/fluxo/assinatura/`): cadastrar usuário → login → solicitar
  assinatura → consultar cobrança → simular aprovação → confirmar `ATIVA`.
- **Renovação (aprovação)** (`bruno/fluxo/renovacao/`): a renovação dispara
  sozinha; consultar renovação → simular aprovação → confirmar ciclo avançado.
- **Renovação (esgotamento)**: simular recusa 3× → confirmar `SUSPENSA`.

Para testar o tempo de renovação em segundos em vez de dias, use o perfil de
teste rápido no `.env`:

```
APP_RENOVACAO_CICLO_MS=60000
APP_RENOVACAO_INTERVALO_MS=1000
APP_RENOVACAO_SCHEDULER_INTERVALO_MS=1000
APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS=0,0
APP_OUTBOX_INTERVALO_MS=1000
```

> `proxima_renovacao_em` é um instante preciso (`timestamptz`): com ciclo menor
> que um dia, a janela `ATIVA` dura exatamente `cicloMs` reais e a renovação
> dispara no sweep seguinte (~1s). Ciclos de um dia ou mais aguardam dias reais.

## Como rodar

Requisito: Docker + Docker Compose.

```bash
docker compose up -d --build
```

Serviços expostos no host:

| Serviço      | URL                    | Validação                                     |
|--------------|------------------------|-----------------------------------------------|
| assinatura   | http://localhost:18080 | `curl http://localhost:18080/actuator/health` |
| pagamento    | http://localhost:18082 | `curl http://localhost:18082/actuator/health` |
| mock gateway | http://localhost:8081  | `curl http://localhost:8081/healthz`          |
| postgres     | localhost:5433         | `pg_isready`                                  |
| redis        | localhost:6379         | `redis-cli ping`                              |
| kafka        | localhost:9092         | healthcheck do compose                        |

> As portas 18080/18082/5433 evitam conflito com outros projetos no host.

## Stack

- **Backend**: Spring Boot 4.1, Java 26, Spring Security (JWT), Spring Data JPA,
  Flyway, Spring Kafka, Spring Session Redis, WebClient.
- **Persistência**: PostgreSQL 17.
- **Cache/Mensageria**: Redis 7, Apache Kafka (KRaft).
- **Mock**: Go (apenas `net/http`, estado em memória).
- **Qualidade**: Checkstyle + Spotless (Google Java Style), ArchUnit, pre-commit
  hook.

## Avisos de segurança

Este repositório carrega valores padrão voltados **apenas para desenvolvimento
local**. Em qualquer ambiente que não seja dev local, sobrescreva-os via
variável de ambiente (`.env` lido pelo `docker-compose.yml`, ou export direto).

- **JWT secret** (`services/assinatura/src/main/resources/application.yaml`): o
  `app.jwt.secret` tem um default fixo em base64. Sobrescreva com `APP_JWT_SECRET`
  em produção. O `docker-compose.yml` não o repete, fazendo a app recorrer a esse
  default; defina a variável para usar um segredo real.
- **Credenciais do Bruno** (`bruno/environments/local.yml`): a senha `admin123` é
  a do usuário administrador seedado na migration inicial, reaproveitada no
  cadastro do cliente de teste da collection, que é quem emite o JWT usado no
  fluxo de assinatura. Não é uma credencial de produção.

> Nenhum `.env` real é versionado. Veja `.env.example` para a lista completa de
> variáveis externalizáveis.

## Desenvolvimento

Cada microserviço é independente e tem seu próprio `Makefile`:

```bash
cd services/assinatura   # ou services/pagamento
make lint      # checkstyle + spotless (não corrige)
make format    # corrige formatação automaticamente
make test      # roda os testes unitários (exclui integração)
make verify    # lint + testes unitários + package
make test-integration   # sobe o Postgres (porta 5433) e roda a integração
```

Variáveis de ambiente em `.env.example`. Convenções de commit, branch, estilo de
código e testes em `AGENTS.md`.
