# Changelog

Todas as mudanças notáveis deste projeto serão documentadas neste arquivo.
Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/).

## [Não publicado]

### Alterado
- **Rotas de assinatura exigem JWT** (ADR 0003, supersede a ADR 0001): `POST
  /assinaturas` e `GET /assinaturas/{uuid}` deixam de ser públicos. Sem token,
  ou com token inválido, a resposta é `401`.
- **`POST /assinaturas` não recebe mais `usuarioId`**: o corpo carrega apenas
  `plano` e o dono vem da claim `usuarioId` do token. *Quebra de contrato.*
- **`GET /assinaturas/{uuid}` só devolve a assinatura ao dono**, com `403` para
  assinatura de terceiro. `ROLE_ADMIN` consulta qualquer uma.
- **Login emite a claim `usuarioId`** com o uuid público do `Usuario` ligado à
  credencial, dispensando consulta ao banco na autorização de cada requisição.
- **Filtro JWT deriva a authority da claim `role`**, em vez de fixar
  `ROLE_USER`, o que passa a distinguir cliente de administrador.
- Collection do Bruno: cadastro → login (como o cliente cadastrado) →
  assinatura, com `Authorization: Bearer` nas rotas de assinatura.

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
- Repositório reorganizado para monorepo de microserviços: `services/assinatura`
  e `services/pagamento`. O mock do gateway fica isolado em `docker/mock-pagamento/`.
- Versão do serviço `assinatura` de `0.0.1-SNAPSHOT` para `0.1.0`.

### Scaffold
- `services/pagamento/`: novo microserviço Spring Boot (Pagamento Service) fiel
  ao diagrama `docs/cadastro-usuario-assinatura.puml`. Scaffold mínimo
  (Application, SecurityConfig com health, application.yaml, Dockerfile, lint).
  Lógica de negócio (consumo de Kafka, gateway, webhook) fica para etapa futura.
- `KAFKA_ADVERTISED_LISTENERS` do compose ajustado para `kafka:9092` (host
  interno da network do compose).
