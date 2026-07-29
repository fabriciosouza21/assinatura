# Roadmap: Docker para todos os serviços + Mock de pagamento

**PRD:** (não há PRD formal; escopo derivado da conversa e dos diagramas `docs/cadastro-usuario-assinatura.puml` e `docs/mock-meio-pagamento.puml`)
**Versão alvo:** `0.1.0` (primeiro milestone com ambiente local completo)
**Branch:** `feat/setup-docker-mock-pagamento`
**Data:** 2026-07-29

## Decisões assumidas

Sem PRD formal, as decisões abaixo foram assumidas com base no scout do projeto.
Corrija qualquer uma antes de começar:

- **Mock sem orquestrador.** Só o Mock Payment API entra no escopo. O Pagamento
  Service do diagrama fica para um roadmap futuro. O serviço `assinatura` chama o
  mock direto.
- **Mock em Go, o mais simples possível.** Mock em Go usando só a biblioteca
  padrão (`net/http`), estado em memória (sem banco), num único binário. Sem
  framework, sem Maven, sem Java. Setup mínimo para o primeiro momento.
- **Monorepo.** Serviço `assinatura` vira `services/assinatura`. Mock em
  `services/mock-pagamento`. `docker-compose.yml` na raiz sobe tudo num comando.
- **Base antes do novo.** Primeiro dockerizamos o que já existe e validamos.
  Depois entram o mock e a integração.

---

## Infraestrutura e Docker

### INFRA-1 — Dockerfile do serviço assinatura
`build: adiciona dockerfile multi-stage do servico assinatura`
- Desenvolvedor consegue buildar a imagem do `assinatura` sem Java/Maven
  instalados localmente.
- Imagem base compatível com Java 26.

### INFRA-2 — Serviço assinatura no docker-compose
`feat: sobe servico assinatura no docker-compose`
- `docker compose up` levanta o app junto com Postgres, Redis e Kafka que já
  existem.
- App aguarda a infra ficar saudável antes de iniciar (healthchecks e
  `depends_on`).

### INFRA-3 — Profiles e externalização de config
`refactor: externaliza configuracao em profiles e .env`
- Config de datasource, Redis, Kafka e secret JWT sai do `application.yaml`
  hardcoded para variáveis de ambiente.
- Cria `application-dev` e `.env.example`. Ninguém precisa editar código para
  apontar para outro ambiente.

---

## Monorepo

### REPO-1 — Reorganiza para monorepo
`refactor: move servico assinatura para services/assinatura`
- Estrutura de repositório acomoda múltiplos serviços lado a lado.
- Build do `assinatura` continua funcionando pelo Maven wrapper a partir do novo
  caminho.

### REPO-2 — Skeleton do mock em Go
`feat: cria servico mock-pagamento em go com net/http`
- Projeto Go mínimo em `services/mock-pagamento` com `go.mod` e `main.go`.
- Sobe um servidor HTTP na porta configurada e responde 200 no healthcheck.
- Sem framework, sem banco. Estado em memória.

---

## Mock de pagamento

### MOCK-1 — Endpoints do gateway mock
`feat: implementa endpoints de pagamento do mock`
- `POST /v1/payments` cria um pagamento e devolve id + status.
- `GET /v1/payments/{id}` consulta status.
- Estado em memória (map com mutex). Sem persistência.
- Endpoint de simulação (`/v1/mock/...`) permite forçar aprovação/recusa para
  testes.
- Contrato igual ao definido em `docs/mock-meio-pagamento.puml`.

### MOCK-2 — Webhook do mock
`feat: adiciona disparo de webhook do mock com assinatura hmac`
- Mock notifica a `notificationUrl` informada na criação do pagamento.
- Headers `X-Mock-Event-Id` e `X-Mock-Signature` (HMAC SHA256) presentes,
  conforme o diagrama.
- Envio assíncrono (goroutine) para não bloquear a resposta da criação.

### MOCK-3 — Docker do mock no compose
`feat: adiciona servico mock-pagamento ao docker-compose`
- Dockerfile multi-stage Go (estágio `golang` builda, estágio final mínimo com o
  binário).
- Mock sobe junto com o resto no `docker compose up`.

---

## Integração

### INT-1 — Cliente do mock no serviço assinatura
`feat: configura cliente http para o mock de pagamento`
- `assinatura` chama o mock pela URL configurada por variável de ambiente.
- No compose, serviços conversam pela network compartilhada por nome de host.

---

## Documentação

### DOCS-1 — README de setup
`docs: adiciona readme com instrucoes de docker compose up`
- Novo desenvolvedor lê o README, roda `docker compose up` e tem o ambiente
  completo.
- Lista o que cada serviço expõe e como validar (ex: chamar `/auth/login`).

### DOCS-2 — Changelog e versão
`chore: bump versao para 0.1.0 e atualiza changelog`
- Atualiza versão do `assinatura` para `0.1.0`.
- `CHANGELOG.md` registra o milestone do ambiente completo.

---

## Ordem dos Entregáveis

| # | Entregável | Depende de | Status |
|---|-----------|-----------|--------|
| 1 | Dockerfile do `assinatura` (`INFRA-1`) | — | [ ] |
| 2 | `assinatura` no docker-compose (`INFRA-2`) | 1 | [ ] |
| 3 | Profiles e `.env` (`INFRA-3`) | 2 | [ ] |
| 4 | Reorganização monorepo (`REPO-1`) | 3 | [ ] |
| 5 | Skeleton do mock em Go (`REPO-2`) | 4 | [ ] |
| 6 | Endpoints do mock (`MOCK-1`) | 5 | [ ] |
| 7 | Webhook do mock (`MOCK-2`) | 6 | [ ] |
| 8 | Mock no compose (`MOCK-3`) | 6 | [ ] |
| 9 | Cliente do mock no `assinatura` (`INT-1`) | 8 | [ ] |
| 10 | README de setup (`DOCS-1`) | 9 | [ ] |
| 11 | Changelog e versão `0.1.0` (`DOCS-2`) | 10 | [ ] |

**Racional da ordem:** primeiro dockerizamos o que existe e validamos a fundação
(1 a 3). Depois reorganizamos o repo para receber o novo serviço (4 a 5). Aí
construímos o mock em Go (6 a 8), plugamos no `assinatura` (9) e fechamos com
docs e versão (10 a 11). Cada passo deixa o ambiente rodando e testável.
