# Convenções

## Repositório

O projeto principal (`assinatura`, Spring Boot 4.1 / Java 26) vive na raiz do
repositório. O mock de meio de pagamento fica isolado em `docker/mock-pagamento/`
para deixar explícito que **não** faz parte do projeto principal, é apenas suporte
de desenvolvimento.

- Raiz (`src/`, `pom.xml`, `mvnw`, `Makefile`) — serviço de assinatura.
- `docker/mock-pagamento/` — mock de pagamento em Go (`net/http`), isolado.
- `docker-compose.yml` orquestra os dois via contexts distintos.

## Commits

Seguir Conventional Commits, em PT-BR, lowercase, sem acento no assunto.

```
feat: adiciona webhook do mock com assinatura hmac
refactor: move servico assinatura para services/assinatura
fix: evita conflito de portas com outros projetos
docs: atualiza readme com instrucoes de setup
test: adiciona smoke test de integracao
chore: bump versao para 0.1.0
```

## Versionamento

Seguir Semantic Versioning. A versão do `assinatura` vive em
`pom.xml` (`<version>`). Tags no formato `X.Y.Z` mantidas em
sincronia com esse valor.

## Branches

Seguir GitFlow.

- `main`: produção. Recebe apenas merges de `release/` e `hotfix/`. Tags de release são criadas aqui.
- `develop`: integração contínua. Todas as features convergem aqui.
- `feature/<nome>`: nova funcionalidade. Origem e destino: `develop`.
- `bugfix/<nome>`: correção de bug. Origem e destino: `develop`.
- `release/<X.Y.Z>`: preparação de versão. Origem: `develop`. Destino: `main` e `develop`.
- `hotfix/<X.Y.Z>`: correção urgente em produção. Origem: `main`. Destino: `main` e `develop`.

Nomes de branch em kebab-case, sem acentos. Verbo no infinitivo descrevendo a entrega.

## Estilo de código — Java

Seguir [Google Java Style](https://google.github.io/styleguide/javaguide.html).

- Checkstyle 11.0.1 com `config/checkstyle/checkstyle.xml`
  (cópia do `google_checks.xml` oficial).
- Indentação 2 espaços, sem tabs, line length 100.
- Spotless com `google-java-format` (1.30.0, estilo GOOGLE) corrige formatação
  automaticamente, remove imports não usados e ordena imports.
- **Javadoc obrigatório** em todo tipo e método público, seguindo o padrão da
  API interna do Java: primeira frase descrevendo o que faz, depois `@param`,
  `@return`, `@throws`. Usar `{@code ...}` para literais de código. Javadoc que
  começa direto com `@return`/`@param` é rejeitado pelo `SummaryJavadoc`.

Comandos (a partir da raiz):

```bash
make lint     # verifica checkstyle + spotless (não corrige)
make format   # corrige formatação automaticamente
make test     # roda os testes
make verify   # pipeline completo: lint + testes + package
```

O hook `pre-commit` (em `.githooks/`) roda `make lint` automaticamente quando há
arquivos `.java` no stage. Bypass: `SKIP_PRE_COMMIT=1 git commit ...`.

## Estilo de código — Go (`docker/mock-pagamento`)

Mock isolado de meio de pagamento. **Não faz parte do projeto principal.**

- Apenas biblioteca padrão (`net/http`), sem framework.
- `gofmt` para formatação. Estado em memória (map + mutex), sem persistência.
- Contrato do mock em `docs/mock-meio-pagamento.puml`.

Comandos (a partir de `docker/mock-pagamento`):

```bash
go vet ./...   # análise estática
go build .     # compilação
```

## Configuração

Todas as configurações são externalizadas por variáveis de ambiente. Veja
`.env.example` para a lista completa (datasource, redis, kafka, JWT, mock). O
`docker-compose.yml` lê `.env` se existir; sem ele, usa defaults de dev local.

Portas publicadas no host (evitam conflito com outros projetos):

- `assinatura` app: `18080`
- `mock-pagamento`: `8081`
- `postgres`: `5433`
- `redis`: `6379`
- `kafka`: `9092`

## Documentação

- Diagramas de sequência em `docs/*.puml`.
- Roadmap em `docs/roadmap/`.
- Histórico de mudanças em `CHANGELOG.md`.
- Setup e validação em `README.md`.
