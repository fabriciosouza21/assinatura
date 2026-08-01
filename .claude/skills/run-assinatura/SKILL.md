---
name: run-assinatura
description: Build, run, and drive the Sistema de Assinaturas (assinatura + pagamento microservices + mock payment gateway, orchestrated via docker-compose). Use when asked to start the stack, run it end-to-end, hit its API, drive the subscription flow, or check its health.
---

Two Spring Boot 4.1 / Java 26 microservices (`services/assinatura`,
`services/pagamento`) plus a Go mock payment gateway
(`docker/mock-pagamento`), talking over Kafka, backed by Postgres/Redis,
orchestrated by the root `docker-compose.yml`. There is no useful way to run
`assinatura` alone — drive the whole stack via `docker compose` and the
`curl`-based driver at `.claude/skills/run-assinatura/driver.sh`.

All paths below are relative to the repo root (where `docker-compose.yml` lives).

## Prerequisites

Docker + Docker Compose v2 (`docker compose`, not `docker-compose`). Verified with:

```bash
docker compose version   # v5.3.1 in this container
docker version --format '{{.Server.Version}}'   # 29.6.2
```

`jq` and `curl` for the driver (both already present in this container).

## Build + Run

```bash
docker compose up -d --build
```

Builds `assinatura`, `pagamento` and `mock-pagamento` images (multi-stage
Maven/Go builds) and starts all 7 containers: `postgres`, `redis`, `kafka`,
`jaeger`, `mock-pagamento`, `pagamento`, `assinatura`. No `.env` is required —
`docker-compose.yml` reads one if present (`env_file: required: false`) but
every var has a working dev default (see `.env.example`).

`assinatura` and `pagamento` take a few seconds after container start before
their healthcheck flips to `healthy` (Spring Boot startup). The driver's
`health` command already retries — don't assume "container Up" means
"API ready."

## Run (agent path)

Drive it with `.claude/skills/run-assinatura/driver.sh` — it replicates the
end-to-end flow from `bruno/README.md` ("Fluxo encadeado completo") with
plain `curl` + `jq`, no GUI client needed:

```bash
.claude/skills/run-assinatura/driver.sh flow
```

This: waits for all 3 HTTP healthchecks → cadastra um usuário novo (email
com timestamp, sempre único) → solicita uma assinatura PREMIUM → consulta a
cobrança no Pagamento Service para obter o `paymentId` gerado pelo gateway →
simula a aprovação no mock gateway → faz polling em `GET /assinaturas/{id}`
até o status virar `ATIVA`. Exits non-zero and logs which step failed if the
chain breaks anywhere — that's the signal something regressed.

Verified output (this session):

```
[driver] checando healthchecks...
assinatura: {"groups":["liveness","readiness"],"status":"UP"}
pagamento:  {"groups":["liveness","readiness"],"status":"UP"}
mock:       {"status":"UP"}
[driver] 1/5 cadastrando usuario...
[driver] usuarioId=6b0f3b02-a5b3-460d-bc09-4d166d9c5569
[driver] 2/5 solicitando assinatura PREMIUM...
[driver] assinaturaId=a3e3ddb1-42ff-4a80-90b9-1bbc142a59e3 status=AGUARDANDO_PAGAMENTO
[driver] 3/5 consultando cobranca no Pagamento Service (ponte assinatura -> pagamento)...
[driver] paymentId=36197cbf-ef73-4dd2-9ac8-194e143cbce8
[driver] 4/5 simulando aprovacao no mock gateway...
{"paymentId":"...","previousStatus":"PENDING","status":"APPROVED","webhookScheduled":true}
[driver] 5/5 aguardando webhook -> kafka -> assinatura ATIVA...
{"id":"a3e3ddb1-...","status":"ATIVA","dataInicio":"2026-08-01","dataExpiracao":"2026-09-01",...}
[driver] OK: assinatura a3e3ddb1-9260... chegou a ATIVA
```

Other driver subcommands:

| command | what it does |
|---|---|
| `driver.sh flow` (default) | full E2E chain above |
| `driver.sh health` | just polls the 3 healthchecks and prints their bodies |
| `driver.sh login` | `POST /auth/login` with the seeded `admin`/`admin123`, prints the JWT — note the exercised flow above never needs this token; `POST /usuarios`, `POST /assinaturas` and `GET /assinaturas/**` are `permitAll` by design (see `services/assinatura/.../security/SecurityConfig.java`) |

Env overrides if ports differ: `ASSINATURA_URL`, `PAGAMENTO_URL`, `MOCK_URL`, `SENHA`.

### Direct invocation (single request, no chain)

For a one-off check against a specific endpoint, skip the driver:

```bash
curl -s http://localhost:18080/actuator/health
curl -s -X POST http://localhost:18080/usuarios -H "Content-Type: application/json" \
  -d '{"nome":"X","email":"x@example.com","senha":"admin123"}'
```

The `bruno/` directory has one `.yml` request per endpoint (OpenCollection
format) if you want the exact documented shape of every call, including the
mock-gateway-only ones (`criar-pagamento`, `consultar-pagamento`) that the
driver doesn't exercise directly.

## Run (human path)

Same `docker compose up -d --build`, then hit the URLs from a browser/Bruno:
`assinatura` on `:18080`, `pagamento` on `:18082`, mock gateway on `:8081`,
Jaeger UI on `:16686` (the stack ships OpenTelemetry/OTLP end-to-end per the
recent `feat(observabilidade)` commits — not independently verified in this
session, but the Jaeger container is up and healthy). `docker compose down`
to stop; add `-v` to also drop the Postgres volume.

## Test

Per-service, from inside `services/assinatura` or `services/pagamento`:

```bash
make test      # unit tests, excludes @Tag("integration")
make verify    # checkstyle + spotless + unit tests + package
```

Confirmed on `services/assinatura` this session (`develop`): `make verify` →
83 tests, 0 checkstyle violations, spotless clean, `BUILD SUCCESS`.

`make test-integration` also exists per-service but was **not** run in this
session — see Gotchas below before running it against a stack you care about.

## Gotchas

- **`make test-integration` will tear down the whole demo stack, not just
  its own Postgres.** Its `test-integration-clean`/`up` targets run
  `docker compose -f ../../docker-compose.yml down -v --remove-orphans` /
  `up -d --wait postgres` — that's the *same* compose project as the root
  `docker compose up`, so `down -v` kills `assinatura`, `pagamento`, `kafka`,
  `mock-pagamento`, `jaeger` too and drops the Postgres volume. Don't run it
  in a terminal next to a stack you're using for manual/driver testing;
  bring the full stack back up afterward with `docker compose up -d --build`
  from the repo root.
- **A couple of `Unable to rollback against JDBC Connection` errors in
  `assinatura` logs right after `docker compose up` are expected and
  harmless** — a scheduled outbox-publisher tick lands mid-restart while
  Postgres is still coming up. They stopped appearing within ~20s in this
  session and the E2E flow was unaffected; only worry if they keep recurring
  after the healthcheck goes green.
- **`POST /assinaturas` currently returns `404` for a malformed (non-UUID)
  `usuarioId`**, not the `400` the OpenAPI doc describes — confirmed by
  sending `usuarioId: "nao-e-um-uuid"`. `AssinaturaRequest.usuarioId` only
  has `@NotBlank`, no format validation, so it falls through to
  `usuarioRepository.findByUuid(...)` and 404s like a well-formed-but-unknown
  id would. Not something this skill works around — just don't be surprised
  when a "malformed id" test expects 400 and gets 404.
- **The `consultar cobranca` step is the bridge between services** — it's
  not optional. `POST /assinaturas` only returns `AGUARDANDO_PAGAMENTO`; the
  `paymentId` needed to call the mock gateway lives in the Pagamento
  Service's own DB and only appears there once it's consumed
  `AssinaturaSolicitada` off Kafka. The driver retries `GET
  /cobrancas/{assinaturaId}` for exactly this reason — a single shot right
  after `solicitar-assinatura` can 404 if Kafka delivery hasn't landed yet.

## Troubleshooting

- **`docker compose ps` shows `assinatura`/`pagamento` stuck on `(health:
  starting)`**: normal for the first ~10-15s after container start (Spring
  Boot boot time). The driver's `health` command polls with retries — don't
  hand-roll a single `curl` right after `up -d`.
- **Driver step 3 (`consultar cobranca`) times out**: check
  `docker compose logs kafka pagamento --tail 50` — usually means Kafka
  wasn't `healthy` yet when `pagamento` started consuming, or the topic
  wasn't created. A restart (`docker compose restart pagamento`) is the
  fastest recheck.
