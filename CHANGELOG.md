# Changelog

Todas as mudanças notáveis deste projeto serão documentadas neste arquivo.
Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/).

## [0.1.0] - 2026-07-29

Primeiro milestone: ambiente local completo sobe com `docker compose up`.

### Adicionado
- Dockerfile multi-stage do serviço `assinatura` (Java 26, build sem Java/Maven locais).
- Serviço `assinatura` no `docker-compose.yml` com healthcheck e `depends_on`
  condicional em postgres, redis e kafka.
- Externalização de configuração: datasource, redis, kafka e JWT via variáveis
  de ambiente, com perfil `application-dev` e `.env.example`.
- `spring-boot-starter-actuator` e exposição pública do `/actuator/health`.
- Reorganização do repositório: projeto principal (`assinatura`) na raiz, mock
  de pagamento isolado em `docker/mock-pagamento/`.
- Mock de pagamento em Go (`net/http`, estado em memória):
  - `POST /v1/payments` (idempotente por `Idempotency-Key`).
  - `GET /v1/payments/{id}` (consulta de status).
  - `POST /v1/mock/payments/{id}/status` (simulação de aprovação/recusa).
  - Webhook assíncrono para `notificationUrl` com `X-Mock-Event-Id` e
    `X-Mock-Signature` (HMAC SHA256).
  - Dockerfile multi-stage Go e serviço no compose.
- Smoke test de integração validando a comunicação inter-serviços por nome de
  host na network do compose.
- `README.md` com instruções de setup e validação.

### Alterado
- Versão do serviço `assinatura` de `0.0.1-SNAPSHOT` para `0.1.0`.
- `KAFKA_ADVERTISED_LISTENERS` do compose ajustado para `kafka:9092` (host
  interno da network do compose).
