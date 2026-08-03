# Changelog

Todas as mudanças notáveis deste projeto serão documentadas neste arquivo.
Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/).

## [Não publicado]

### Adicionado
- **Renovação automática** (Assinatura Service): assinatura ganha ciclo
  (`inicioCiclo`, `fimCiclo`, `proximaRenovacaoEm`, `renovacaoAutomatica`),
  status `EM_RENOVACAO`, `SUSPENSA` e `CANCELADA`, registro de renovação por
  ciclo e agendador que varre vencimentos com `FOR UPDATE SKIP LOCKED`. A
  duração do ciclo é configurável via `APP_RENOVACAO_CICLO_MS`.
- **Outbox publica pelo tipo do evento**: a rota `eventType → tópico` sai de
  configuração, permitindo eventos de renovação e cancelamento em tópicos
  próprios.
- **Resultado de renovação**: o Assinatura Service consome
  `PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas` com dedup por
  `eventId`; aprovação rola o ciclo, três recusas suspendem a assinatura.
- **Pagamento Service publica resultados via outbox**: `PagamentoStatusAtualizado`,
  `PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas` saem da outbox
  com retry, backoff com jitter e DLQ.
- **Cobrança de renovação no Pagamento Service**: consome `RenovacaoSolicitada`,
  cria pagamento no gateway com idempotência e agenda novas tentativas após
  recusa (`APP_RENOVACAO_TENTATIVAS_BACKOFF_DIAS`); falha técnica do gateway
  não consome tentativa.
- **Webhook decide a renovação**: o webhook do gateway com assinatura HMAC
  publica o resultado da cobrança de renovação antes de confirmar o gateway.
- **Consulta de renovação**: `GET /renovacoes/{assinaturaId}` no Pagamento
  Service retorna o pagamento de renovação mais recente de uma assinatura
  (`paymentId` e status da tentativa corrente).
- **Consulta de assinatura expõe o ciclo de renovação** (`inicioCiclo`,
  `fimCiclo`, `proximaRenovacaoEm`, `renovacaoAutomatica`).
- **Cancelamento de assinatura**: `POST /assinaturas/{uuid}/cancelamento`
  (JWT do dono) publica `CancelamentoAgendado` (cancela no fim do ciclo, para
  assinatura com vigência ativa) ou `AssinaturaCancelada` (imediato, para os
  demais status). O Pagamento Service consome o evento com dedup e abandona
  tentativas de cobrança pendentes.
- Collection do Bruno com fluxos de renovação (aprovação e esgotamento),
  incluindo consulta de renovação no Pagamento Service.
- **Consulta da assinatura ativa**: `GET /assinaturas/ativa` autenticado (JWT
  do dono) devolve a assinatura `ATIVA` do usuário como `AssinaturaResponse`,
  com `404` sem ativa e `403` para `ROLE_ADMIN`. Cache-aside em Redis por
  usuário com cache negativo da ausência (TTL 5 min) e invalidação via
  contador de versão compartilhado com a listagem.

### Alterado
- **Precisão de tempo na renovação** (Assinatura Service): `proximaRenovacaoEm`
  deixa de ser `DATE` e vira instante preciso (`timestamptz`); com ciclo
  sub-diário, a janela `ATIVA` dura exatamente `APP_RENOVACAO_CICLO_MS` reais,
  e a consulta passa a expor o campo como `date-time` ISO-8601. Entra no 0.3.0,
  sem bump separado.
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
- **Pacotes reorganizados por capacidade de negócio** (ADR 0002): `adesao`,
  `cadastro`, `renovacao`, `cancelamento` etc. nos dois serviços, com regra de
  dependência `capacidade → núcleo → shared` protegida por testes ArchUnit.
- **Guards das consultas rejeitam `usuarioId` vazio**: `GET /assinaturas` e
  `GET /assinaturas/ativa` respondem `403` também para a claim `""`, não só
  `null` (defesa em profundidade contra bug futuro no login).

## [0.4.0] - 2026-08-02

### Adicionado
- **Listagem paginada de assinaturas**: `GET /assinaturas` autenticado lista
  as assinaturas do dono do token com `page` (default 0) e `size` (default 20,
  máx. 100), ordenadas por `id DESC` e paginadas em `AssinaturaLista`
  (`items` + `page` + `size` + `total`). `401` sem token, `403` para
  administrador. Índice `(usuario_id, id DESC)` na migration `V10`.
- **Cache Redis na listagem**: cache-aside com chave versionada
  `assinatura:list:{usuarioUuid}:v{n}:{page}:{size}` e contador INCR por
  usuário, TTL configurável (default 5 min) via `APP_CACHE_ASSINATURA_LISTA_TTL_MS`.
  A versão é invalidada after-commit nos 5 fluxos de escrita (solicitação,
  confirmação de pagamento, resultado de renovação, scheduler e cancelamento).
  Redis indisponível degrada para o banco com `WARN`, sem derrubar a listagem.

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
