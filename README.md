# assinatura

Serviço de assinatura (Spring Boot 4.1 / Java 26) com mock de meio de pagamento
em Go. Ambiente local completo sobe com um único comando.

## Requisitos

- Docker + Docker Compose

Não é preciso ter Java, Maven ou Go instalados localmente. As imagens são
multi-stage e contêm tudo o que precisam.

## Subindo o ambiente

```bash
cp .env.example .env      # opcional: ajuste valores conforme o ambiente
docker compose up -d --build
```

Serviços disponíveis após o `up`:

| Serviço           | Host (no compose)            | Host (máquina)         | Como validar                                          |
|-------------------|------------------------------|------------------------|-------------------------------------------------------|
| assinatura (app)  | `assinatura:8080`            | http://localhost:18080 | `curl http://localhost:18080/actuator/health` → `UP`  |
| pagamento (app)   | `pagamento:8080`             | http://localhost:18082 | `curl http://localhost:18082/actuator/health` → `UP`  |
| mock-pagamento    | `mock-pagamento:8081`        | http://localhost:8081  | `curl http://localhost:8081/healthz` → `UP`          |
| postgres          | `postgres:5432`              | localhost:5433         | `pg_isready`                                          |
| redis             | `redis:6379`                 | localhost:6379         | `redis-cli ping`                                      |
| kafka             | `kafka:9092`                 | localhost:9092         | healthcheck do compose                                |

A app aguarda a infra (postgres, redis, kafka) ficar saudável antes de iniciar,
via `depends_on` + healthchecks.

## Validação rápida

Health da aplicação (após subir, ~10-15s de boot):

```bash
curl http://localhost:18080/actuator/health
# {"status":"UP"}
```

Login (credenciais inválidas respondem 4xx, o que confirma a rota ativa):

```bash
curl -X POST http://localhost:18080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"x"}'
```

Smoke test de integração entre assinatura e mock (resolução por nome de host na
network do compose):

```bash
./docker/mock-pagamento/test/integration-smoke.sh
```

## Estrutura do repositório

```
.
├── docker-compose.yml          # orquestra todos os serviços
├── .env.example                # template de configuração
├── services/
│   ├── assinatura/             # Assinatura Service (Spring Boot, Java 26)
│   └── pagamento/              # Pagamento Service (Spring Boot, Java 26)
├── docs/                       # diagramas e roadmap
└── docker/
    └── mock-pagamento/         # Mock do gateway de pagamento (Go) — NÃO é um microserviço
```

Os dois microserviços Spring Boot (assinatura e pagamento) conversam via Kafka.
O mock em `docker/` apenas simula o gateway de pagamento externo para
desenvolvimento local.

## Variáveis de ambiente

Todas as configurações são externalizadas. Veja `.env.example` para a lista
completa (datasource, redis, kafka, JWT, mock). O `docker-compose.yml` lê `.env`
se existir; sem ele, usa defaults adequados ao ambiente local.

## Parando o ambiente

```bash
docker compose down            # remove containers, mantém volumes
docker compose down -v         # remove também os volumes (apaga dados do postgres/redis)
```
