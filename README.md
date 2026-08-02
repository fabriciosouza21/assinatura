# Sistema de Assinaturas

Desafio técnico — sistema de gestão de assinaturas para um serviço de streaming.
Usuários assinam planos mensais e a cobrança ocorre automaticamente.

## Contexto

Dois microserviços Spring Boot (Java 26) que conversam via Kafka, mais um mock do
gateway de pagamento para desenvolvimento local.

```
assinatura  ──AssinaturaSolicitada──▶  kafka  ──▶  pagamento  ──▶  gateway (mock)
     ▲                                                              │
     └──────PagamentoStatusAtualizado───── kafka ◀─── webhook ◀─────┘
```

- **`services/assinatura`** — cadastra usuários e assinaturas, publica
  `AssinaturaSolicitada` via outbox, consome `PagamentoStatusAtualizado` para
  ativar/suspender.
- **`services/pagamento`** — consome `AssinaturaSolicitada`, fala com o gateway,
  recebe webhooks e publica `PagamentoStatusAtualizado`.
- **`docker/mock-pagamento`** — mock do gateway de pagamento (Go, `net/http`).
  Não é um microserviço, apenas suporte de dev.

## O que está pronto

- **Infraestrutura completa** sobe com um comando via `docker compose up`.
- **Assinatura Service**: autenticação JWT obrigatória nas rotas de assinatura
  (o dono vem do token, ver ADR 0003), cadastro de usuário, adesão com status
  `AGUARDANDO_PAGAMENTO`, persistência com Postgres + Flyway, healthcheck via
  Actuator.
- **Renovação automática** (Assinatura Service): agendador varre assinaturas
  com `proxima_renovacao_em` vencida, cria a renovação e publica
  `RenovacaoSolicitada` via outbox; consome o resultado e avança o ciclo ou
  suspende após tentativas esgotadas.
- **Pagamento Service**: consome `AssinaturaSolicitada` e `RenovacaoSolicitada`,
  cobra o gateway com idempotência, recebe webhooks com assinatura HMAC e
  publica os eventos de status.
- **Mock do gateway**: cria pagamento (idempotente), consulta status, simula
  aprovação/recusa, dispara webhook assíncrono com assinatura HMAC.
- **Outbox**: eventos de domínio publicados via outbox com retry e DLQ.
- **Listagem de assinaturas**: `GET /assinaturas` paginado (dono do token), com
  cache Redis cache-aside e invalidação por versão nos fluxos de escrita.
- **Testes**: unitários por serviço, testes de integração isolados, arquitetura
  (ArchUnit) e checkstyle/spotless no pipeline.

## O que falta (escopo do desafio ainda não entregue)

- Endpoint de cancelamento (o opt-out no vencimento existe no domínio).

A íntegra do escopo e do andamento está em `docs/roadmap/`.

## Cache da listagem de assinaturas

O `GET /assinaturas` é cache-aside com **chave versionada por usuário**:
`assinatura:list:{usuarioUuid}:v{n}:{page}:{size}`, com TTL padrão de 5
minutos (`APP_CACHE_ASSINATURA_LISTA_TTL`).

A invalidação não apaga chave: ela é um **contador INCR por usuário**
(`assinatura:list:versao:{usuarioUuid}`) no Redis. Cada fluxo de escrita
(solicitação, confirmação de pagamento, resultado de renovação, scheduler e
cancelamento) incrementa o contador após o commit da transação. Como a leitura
monta a chave com a versão corrente, a geração anterior fica inalcançável e
expira no TTL.

Essa estratégia é segura com o cache distribuído entre réplicas: o `INCR` é
atômico no servidor Redis (compartilhado por todas as instâncias), a versão só
aumenta, e um leitor atrasado que grave dado velho o faz em uma chave órfã,
nunca servida. Elimina a corrida de repopulação do `DEL` (apagar + outra
instância recachear dado velho) sem lock distribuído — em vez de excluir
escritas concorrentes, garante que só a geração mais nova seja lida.

Se o Redis falhar, a leitura degrada para o banco com `WARN` e a escrita de
invalidação é coberta pelo TTL: consistência eventual em segundos, nunca erro
da request.

## Como testar via Bruno

A collection [Bruno](https://docs.usebruno.com/) em `bruno/` cobre os fluxos
de ponta a ponta, encadeando requests sem editar variáveis manualmente. O
detalhamento de cada fluxo está em `bruno/README.md`; o resumo:

1. Suba o ambiente: `docker compose up -d --build`.
2. No Bruno: **Open Collection** → pasta `bruno/` → environment **Local**.

Fluxos disponíveis:

- **Adesão**: cadastrar usuário → login → solicitar assinatura → consultar
  cobrança → simular aprovação → confirmar `ATIVA`.
- **Renovação (aprovação)**: após a ativação, a renovação dispara sozinha;
  consultar renovação → simular aprovação → confirmar ciclo avançado.
- **Renovação (esgotamento)**: simular recusa 3× → confirmar `SUSPENSA`.

Para testar o tempo de renovação em segundos em vez de dias, use o perfil de
teste rápido no `.env` (detalhes em `bruno/README.md`):

```
APP_RENOVACAO_CICLO_MS=60000
APP_RENOVACAO_INTERVALO_MS=1000
APP_RENOVACAO_SCHEDULER_INTERVALO_MS=1000
APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS=0,0
APP_OUTBOX_INTERVALO_MS=1000
```

> `proxima_renovacao_em` é uma data (DATE): com ciclo menor que um dia, a
> renovação vence "hoje" e dispara no próximo sweep (~1s). Cada aprovação
> encadeia o ciclo seguinte imediatamente; ciclos de um dia ou mais aguardam
> dias reais.

## Como rodar

Requisito: Docker + Docker Compose.

```bash
docker compose up -d --build
```

Serviços expostos no host:

| Serviço        | URL                          | Validação                                     |
|----------------|------------------------------|-----------------------------------------------|
| assinatura     | http://localhost:18080       | `curl http://localhost:18080/actuator/health` |
| pagamento      | http://localhost:18082       | `curl http://localhost:18082/actuator/health` |
| mock gateway   | http://localhost:8081        | `curl http://localhost:8081/healthz`          |
| postgres       | localhost:5433               | `pg_isready`                                  |
| redis          | localhost:6379               | `redis-cli ping`                              |
| kafka          | localhost:9092               | healthcheck do compose                        |

> As portas 18080/18082/5433 evitam conflito com outros projetos no host.

## Stack

- **Backend**: Spring Boot 4.1, Java 26, Spring Security (JWT), Spring Data JPA,
  Flyway, Spring Kafka, Spring Session Redis, WebClient.
- **Persistência**: PostgreSQL 17.
- **Cache/Mensageria**: Redis 7, Apache Kafka (KRaft).
- **Mock**: Go (apenas `net/http`, estado em memória).
- **Qualidade**: Checkstyle + Spotless (Google Java Style), pre-commit hook.

## Avisos de segurança

Este repositório carrega valores padrão voltados **apenas para desenvolvimento
local**. Em qualquer ambiente que não seja dev local, sobrescreva-os via
variável de ambiente (`.env` lido pelo `docker-compose.yml`, ou export direto).

- **JWT secret** (`services/assinatura/src/main/resources/application.yaml`):
  o `app.jwt.secret` tem um default fixo em base64. Sobrescreva com
  `APP_JWT_SECRET` em produção. O `docker-compose.yml` não o repete, fazendo a
  app recorrer a esse default; defina a variável para usar um segredo real.
- **Credenciais do Bruno** (`bruno/environments/local.yml`):
  a senha `admin123` é a do usuário administrador seedado na migration inicial,
  reaproveitada no cadastro do cliente de teste da collection — que é quem emite
  o JWT usado no fluxo de assinatura. Não é uma credencial de produção.

> Nenhum `.env` real é versionado. Veja `.env.example` para a lista completa de
> variáveis externalizáveis.

## Desenvolvimento

Cada microserviço é independente e tem seu próprio `Makefile`:

```bash
cd services/assinatura   # ou services/pagamento
make lint      # checkstyle + spotless (não corrige)
make format    # corrige formatação automaticamente
make test      # roda os testes
make verify    # pipeline completo
```

Variáveis de ambiente em `.env.example`. Convenções em `AGENTS.md`.
