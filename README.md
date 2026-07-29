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
- **Assinatura Service**: autenticação JWT, cadastro de usuário, persistência com
  Postgres + Flyway, healthcheck via Actuator.
- **Pagamento Service**: scaffold (entry point + security + config).
- **Mock do gateway**: cria pagamento (idempotente), consulta status, simula
  aprovação/recusa, dispara webhook assíncrono com assinatura HMAC.
- **Integração**: smoke test valida a comunicação inter-serviços por nome de host
  na network do compose.

## O que falta (escopo do desafio ainda não entregue)

- Endpoint de criação de assinatura com a regra "um usuário, uma assinatura ativa".
- Agendador de renovação automática no vencimento + suspensão após 3 falhas.
- Endpoint de cancelamento.
- Consumo de Kafka e handler de webhook no Pagamento Service.
- Outbox real no Assinatura Service.
- Testes automatizados (além do smoke test e do `contextLoads`).

A íntegra do escopo e do andamento está em `docs/roadmap/`.

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
