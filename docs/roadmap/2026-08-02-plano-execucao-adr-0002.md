# Plano de execução — ADR 0002: organização de pacotes por capacidade

**ADR:** `docs/adr/0002-organizacao-de-pacotes-por-capacidade.md`
**Status:** Concluído — pendente apenas do merge para aceitar a ADR
**Versão mantida:** `0.2.0`
**Branch base:** `develop`
**Branch de execução:** `refactor/pacotes-por-capacidade`
**Data:** 2026-08-02

**Execução:** concluída em 2026-08-02. `make verify` passou nos dois serviços;
as integrações passaram com 21 testes no Assinatura Service e 20 no Pagamento
Service; o Compose completo iniciou os dois aplicativos e os health endpoints
retornaram `UP`. A ADR permanece `Proposed` até o merge, conforme definido em
REF-8.

## Objetivo

Reorganizar os dois serviços segundo a estrutura definida na ADR 0002:

`capacidade → núcleo → shared`

Cada capacidade passará a concentrar seus casos de uso e suas fronteiras
(`api/`, `evento/`, `agendador/` e `idempotencia/`, quando aplicável). O refactor
deve preservar o comportamento atual, os contratos REST e Kafka, o schema do
banco e a autonomia de deploy dos serviços.

## Decisões de execução

- A execução será sequencial, com uma etapa validada antes da próxima.
- O trabalho será feito em uma única branch: `refactor/pacotes-por-capacidade`.
- Cada etapa terá um commit lógico `refactor:` e usará `git mv` quando houver
  apenas movimentação ou renomeação.
- Os testes acompanharão a movimentação dos pacotes e preservarão seus cenários.
- `make verify` será executado no serviço alterado ao final de cada etapa.
- Testes de integração só serão executados na etapa final, via
  `make test-integration`.
- Não haverá migration, mudança de contrato, alteração de endpoint, bump de
  versão ou atualização do `CHANGELOG.md`.

## Escopo

### Incluído

- Separação de infraestrutura em `shared/` nos dois serviços.
- Separação dos núcleos de domínio e das capacidades de negócio.
- Movimentação dos consumers para `evento/` das capacidades correspondentes.
- Movimentação das tabelas de deduplicação para `idempotencia/`.
- Organização dos controllers, DTOs e handlers em `api/`.
- Renomeação de `User` para `Credencial`, mantendo a tabela `users`.
- Escopo explícito dos `@RestControllerAdvice`.
- Regras ArchUnit para proteger as dependências entre zonas, capacidades e
  fronteiras.

### Fora de escopo

- Implementação do cancelamento de assinatura.
- Alteração de regras de negócio, contratos REST ou eventos Kafka.
- Criação ou alteração de migrations, tabelas ou índices.
- Alteração do mock de pagamento, Docker Compose, tracing ou logging não
  relacionado à movimentação.
- Criação de módulo compartilhado entre os dois microserviços.
- Release ou mudança de versão.

## Pré-condições

1. Atualizar a referência local de `develop` e confirmar que o worktree principal
   está limpo.
2. Criar `refactor/pacotes-por-capacidade` a partir de `develop`.
3. Executar o baseline de `make verify` em `services/assinatura` e
   `services/pagamento`.
4. Confirmar que a implementação de resultado da renovação já está em `develop`.
   No estado atual, a branch `feat/prd-be10-consome-resultado-renovacao` não
   possui commits à frente de `develop`.
5. Manter `feat/cancelamento-assinatura` fora da branch do refactor. A existência
   dessa worktree não bloqueia a execução e ela não deve ser removida
   automaticamente.

## Entregáveis sequenciais

### REF-0 — Baseline e congelamento da fila

`chore: prepara refactor de pacotes por capacidade`

- Registrar o estado inicial dos dois serviços e confirmar os testes verdes.
- Não incorporar mudanças de cancelamento ou outras features paralelas durante o
  refactor.

**Validação:** `make verify` verde nos dois serviços antes de qualquer movimento.

### REF-1 — Shared do Assinatura Service

`refactor: move infraestrutura do assinatura para shared`

- Mover outbox, política de retry, Kafka, rotas de tópicos, contratos de evento,
  segurança, web e configuração de tempo para os subpacotes de `shared/`.
- Separar a configuração técnica do Kafka da configuração da outbox, eliminando
  a dependência circular entre as duas áreas.
- Manter os DTOs de eventos duplicados neste serviço; não criar dependência no
  Pagamento Service.
- Espelhar a mudança nos testes sem alterar os cenários.

**Validação:** `make verify` em `services/assinatura` e teste de inicialização
dos contextos afetados.

### REF-2 — Shared do Pagamento Service

`refactor: move infraestrutura do pagamento para shared`

- Mover Kafka, rotas de tópicos, contratos de evento e segurança para `shared/`.
- Mover também a outbox atualmente usada pelos webhooks e pelos resultados de
  renovação para `shared/outbox/`. Essa é uma adaptação necessária ao estado
  atual do código, embora o desenho resumido da ADR não liste a outbox do
  Pagamento Service.
- Manter `gateway/` como adapter de saída próprio do Pagamento Service.
- Preservar as configurações externalizadas e as migrations existentes.

**Validação:** `make verify` em `services/pagamento` e testes unitários da
publicação da outbox.

### REF-3 — Núcleos do Assinatura Service

`refactor: separa nucleos de dominio do assinatura`

- Reduzir `assinatura/` ao agregado de assinatura, seu repository, status, plano
  e exceção de não encontrado.
- Manter o núcleo `usuario/` plano, contendo `Usuario`, `Credencial` e seus
  repositories.
- Renomear `User` para `Credencial` sem alterar a tabela `users`, suas colunas ou
  o vínculo com `usuarios`.
- Encaminhar exceções e comandos que pertencem a uma capacidade para a etapa da
  capacidade correspondente.

**Validação:** `make verify` em `services/assinatura`; testes de domínio e
persistência continuam verdes.

### REF-4 — Capacidades do Assinatura Service

`refactor: organiza capacidades do assinatura`

Executar as capacidades nesta ordem, mantendo um commit e um gate de validação
para cada grupo:

1. `adesao/`: solicitação, confirmação do pagamento, API, consumer e deduplicação
   do evento de pagamento.
2. `consulta/`: query, API de consulta e resposta de leitura.
3. `renovacao/`: agregado e repository de renovação, command de solicitação,
   resultado, scheduler, consumer e deduplicação.
4. `cadastro/`: cadastro de usuário, API e handler de cadastro.
5. `auth/`: autenticação, API e handler de credenciais inválidas.

Os testes devem espelhar a mesma anatomia. Controllers não devem permanecer na
raiz de uma capacidade, e consumers não devem permanecer em `shared/kafka/`.

**Validação:** `make verify` em `services/assinatura` após cada grupo.

### REF-5 — Núcleos e capacidades do Pagamento Service

`refactor: organiza capacidades do pagamento`

Executar as capacidades nesta ordem, mantendo um commit e um gate de validação
para cada grupo:

1. Núcleo `cobranca/`: manter apenas agregado, repository, status, plano e
   exceção de domínio; levar consulta e sua API para `consulta/`.
2. `adesao/`: criação da cobrança e consumer de `AssinaturaSolicitada`.
3. `renovacao/`: agregados de pagamento e tentativa, command de criação,
   scheduler, consumers e publishers de resultado.
4. `webhook/`: manter os commands e regras na raiz, levar controller, DTOs,
   validação HMAC e handler para `api/`, e a deduplicação para
   `idempotencia/`.
5. `gateway/`: preservar como adapter de saída plano, fora de `shared/` e das
   capacidades.

**Validação:** `make verify` em `services/pagamento` após cada grupo.

### REF-6 — Fronteiras HTTP explícitas

`refactor: explicita escopo dos handlers rest`

- Declarar cada `@RestControllerAdvice` de capacidade com
  `assignableTypes` para seu controller.
- Manter em `shared/web/` apenas o problema HTTP comum, validação e fallback
  transversal.
- Confirmar que os códigos HTTP e o formato das respostas de erro não mudaram.

**Validação:** testes dos controllers de adesão, consulta, cadastro, auth,
cobrança e webhook.

### REF-7 — Regras arquiteturais

`test: protege organizacao de pacotes por capacidade`

Adicionar um teste de arquitetura em cada serviço, cobrindo no mínimo:

- `shared/` não depende de capacidade nem de núcleo de negócio.
- Uma capacidade não depende de outra capacidade.
- A raiz de uma capacidade não depende de sua própria API.
- Controllers só existem nos subpacotes `api/`.
- Consumers e publishers de negócio só existem nos subpacotes `evento/`.
- Não existem ciclos entre os pacotes de primeiro nível.
- O adapter `gateway/` não depende de capacidades.

As regras devem aceitar as exceções previstas pela ADR, especialmente o acesso
das capacidades ao núcleo, ao `shared/` e ao adapter de gateway do Pagamento
Service.

**Validação:** os testes de arquitetura rodam no `make test` e falham caso uma
regressão reintroduza a organização anterior.

### REF-8 — Validação final e fechamento

`docs: registra execucao da adr 0002`

- Executar `make verify` nos dois serviços.
- Executar os testes de integração de cada serviço, um serviço por vez, sempre
  via `make test-integration` para recriar o Postgres.
- Subir o ambiente local e confirmar que os contextos dos dois serviços iniciam
  com os pacotes reorganizados.
- Registrar na ADR que a outbox do Pagamento Service também é infraestrutura
  técnica e, por isso, fica em `shared/outbox/` dentro daquele serviço.
- Conferir que o diff não contém migrations, alterações de contrato, alteração de
  versão ou mudanças no mock.
- Atualizar o status da ADR para `Accepted` após o merge do refactor e manter o
  link deste plano no histórico da decisão.

## Coordenação de migrations

O refactor não cria migration. No estado atual de `develop`, as próximas
numerações livres são:

- Assinatura Service: `V10`.
- Pagamento Service: `V8`.

Essas numerações devem ser preservadas para as próximas features e não devem ser
antecipadas ou renumeradas durante este trabalho.

## Definition of Done

- [x] Todos os pacotes seguem o alvo da ADR 0002 nos dois serviços.
- [x] A regra `capacidade → núcleo → shared` está protegida por testes.
- [x] O adapter `pagamento/gateway` permanece fora de `shared/`.
- [x] Os testes existentes foram movidos junto com os tipos e permanecem verdes.
- [x] `make verify` passa nos dois serviços.
- [x] Os testes de integração passam via `make test-integration`.
- [x] Não houve alteração de comportamento, schema, contratos, versão ou
  `CHANGELOG.md`.
- [x] ADR 0002 marcada como `Accepted` após o merge (PR #25).

## Ordem dos entregáveis

| # | Entregável | Depende de | Status |
|---|---|---|---|
| 1 | Baseline e congelamento da fila | — | [x] |
| 2 | Shared do Assinatura Service | 1 | [x] |
| 3 | Shared do Pagamento Service | 2 | [x] |
| 4 | Núcleos do Assinatura Service | 3 | [x] |
| 5 | Capacidades do Assinatura Service | 4 | [x] |
| 6 | Núcleos e capacidades do Pagamento Service | 5 | [x] |
| 7 | Fronteiras HTTP explícitas | 6 | [x] |
| 8 | Regras arquiteturais | 7 | [x] |
| 9 | Validação final e fechamento | 8 | [x] |
