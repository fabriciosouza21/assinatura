# Plano de implementação — renovação automática de assinatura 0.3.0

**Versão alvo:** `0.3.0`
**Branch base:** `develop`
**Data:** 2026-07-31 (revisado após o fechamento do 0.2.0)
**Design de referência:** `docs/renovacao/renovacao-automatica-assinatura.puml` e `docs/renovacao/renovacao-automatica-retry-dlq.puml`
**Roadmap (negócio):** `docs/roadmap/2026-07-31-renovacao-automatica.md`
**Nomenclatura:** continua a do 0.2.0 (BE-1…BE-6). Esta release cobre BE-7…BE-13 e DOCS-2.

> **Como usar:** cada track em §5 é autocontida. Um agent lê este doc, o roadmap
> acima, os dois `.puml` de renovação, o `contrato-eventos-kafka.puml` e o
> `AGENTS.md`, escolhe UMA track, implementa com TDD e verifica com `make verify`
> antes do merge.

---

## 0. Premissa: o 0.2.0 está fechado

A renovação reusa a infraestrutura construída no 0.2.0, que já está em `develop`:

- **Webhook do Pagamento** (`WebhookPagamentoController` + `ProcessarWebhookPagamento`):
  valida HMAC via `HmacSignatureValidator`, deduplica por `eventId` em
  `WebhookEventoProcessado`, consulta status oficial no gateway, normaliza e
  publica `PagamentoStatusAtualizado` em **publish-then-ACK** (`kafkaTemplate.send(...).get()`
  antes de persistir o `eventId`).
- **Client do gateway** (`GatewayPagamentoClient`) e a tabela `cobranca` com
  `uq_cobranca_assinatura` (1:1 com `assinatura_uuid`).

Por isso **não há mais gate externo**: o webhook de renovação (BE-13) herda o
endpoint e o padrão já existentes. O único gate é travar o contrato de renovação.

---

## 1. Decisões de arquitetura (travadas)

| ID | Decisão | Racional |
|----|---------|----------|
| D-R1 | **Outbox roteia por `eventType`** (mapa evento→tópico), não mais hardcoded em `assinatura-solicitada` | O `OutboxPublisher` atual publica **todo** evento no tópico fixo `assinatura-solicitada` (`OutboxPublisher.java:77`, tópico lido em `:50`). Para publicar `RenovacaoSolicitada` no tópico certo, o roteamento vira função do `eventType`. Fundação da BE-7. |
| D-R2 | **Gateway agnóstico a renovação.** Mock **intocado**. `externalReference = renovacaoId`, `Idempotency-Key = renovacaoId:tentativa` | O gateway processa pagamentos, não conhece "renovação" nem "tentativa". Esses conceitos são do Pagamento Service. Reusar `POST /v1/payments` resolve. Sem track de mock. |
| D-R3 | **Três eventos novos**, dois tópicos novos | `RenovacaoSolicitada` (AS→PG, tópico `renovacao-solicitada`) e `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas` (PG→AS, tópico `renovacao-resultado`). Os eventos internos `AssinaturaRenovada`/`AssinaturaSuspensa` também passam pela outbox (D-R1) e seguem o mesmo roteamento. |
| D-R4 | **Três contadores de retry independentes**, cada um com seu terminal | (1) `outbox.tentativas` → status `FALHA` (DLQ persistida do produtor). (2) `consumer.tentativas` → Kafka DLT (falha técnica de consumo). (3) `cobranca.tentativas` → suspensão da assinatura (resultado de negócio). São **distintos**: o esgotamento do consumer (DLT) **não** suspende a assinatura. |
| D-R5 | **`Renovacao` é agregado novo**, com `UNIQUE (assinatura_id, ciclo_referencia)` | Criar uma nova `Assinatura` a cada ciclo choca com `uq_assinatura_aberta_usuario` (1 assinatura aberta por usuário). A renovação **estende** a `Assinatura` existente e registra cada ciclo numa linha de `renovacao`. A unicidade por ciclo evita cobrança duplicada. |
| D-R6 | **Pagamento de renovação é agregado novo** (`PagamentoRenovacao` + `TentativaCobranca`), não reusa `Cobranca` | `Cobranca` tem `uq_cobranca_assinatura` (1:1 assinatura). Renovação tem várias cobranças por assinatura ao longo do tempo. Idempotência por `renovacaoId`, não por `assinaturaUuid`. |
| D-R7 | **`Assinatura` ganha campos de ciclo** e novos status | Campos: `inicioCiclo`, `fimCiclo`, `proximaRenovacaoEm`, `renovacaoAutomatica`. Status novos: `EM_RENOVACAO`, `SUSPENSA`, `CANCELADA`. Hoje só há `AGUARDANDO_PAGAMENTO`, `ATIVA`, `PAGAMENTO_RECUSADO` (`StatusAssinatura.java:4`). Método `renovar()` distinto de `ativar()` (que só aceita partir de `AGUARDANDO_PAGAMENTO`). A ativação inicial define o primeiro ciclo; a renovação rola o ciclo para frente. |

---

## 2. Contratos compartilhados (o lock — não mexer sem bump de versão)

Os campos abaixo travam o paralelismo. O artifact formal (`.puml`) é deliverable
do Gate 0 (§4). Até lá, esta tabela é a fonte da verdade.

| Evento | Tópico | Key | Produtor | Consumidor | Campos |
|--------|--------|-----|----------|-----------|--------|
| `RenovacaoSolicitada` | `renovacao-solicitada` | `assinaturaId` | Assinatura (**outbox**) | Pagamento | `eventId` UUID, `ocorridoEm` Instant, `renovacaoId` UUID, `assinaturaId` UUID, `plano` Plano, `valor` BigDecimal, `cicloReferencia` int |
| `PagamentoRenovacaoAprovado` | `renovacao-resultado` | `assinaturaId` | Pagamento (**publish-then-ACK**) | Assinatura | `eventId` UUID, `ocorridoEm` Instant, `renovacaoId` UUID, `assinaturaId` UUID, `paymentId` String, `cicloReferencia` int |
| `RenovacaoTentativasEsgotadas` | `renovacao-resultado` | `assinaturaId` | Pagamento (**publish-then-ACK**) | Assinatura | `eventId` UUID, `ocorridoEm` Instant, `renovacaoId` UUID, `assinaturaId` UUID, `cicloReferencia` int |

- **groupIds:** Pagamento consome `renovacao-solicitada` com `group-id: pagamento`. Assinatura consome `renovacao-resultado` com `group-id: assinatura`. (Mesma convenção do 0.2.0.)
- **Serialização:** String (JSON), Jackson nos records. DTOs duplicados por serviço, pacote `<servico>.messaging.event`.
- **Tópicos:** bean `NewTopics` no serviço **produtor** declara `renovacao-solicitada` (Assinatura) e `renovacao-resultado` + `renovacao-resultado-dlq` (Pagamento). Os `-dlq` de consumer seguem o padrão do 0.2.0.
- **eventId ponta a ponta:** todo consumer deduplica por `eventId` (entrega at-least-once). O `eventId` do webhook (`X-Mock-Event-Id`) propaga para os eventos de resultado.
- **`valor` em BigDecimal (reais):** herda D5 do 0.2.0. Sem conversão para centavos.

---

## 3. Convenções (herdadas do 0.2.0, aplicar em toda track)

- **Branches:** `feat/<nome>` a partir de `develop`, kebak-case, sem acento.
- **Estilo:** Google Java Style; `make format` corrige, `make lint` verifica. Javadoc obrigatório em todo público.
- **Testes:** JUnit 5 + AssertJ, `@DisplayName`, mensagens `.as(...)`. Excluir `@Tag("integration")` do `make verify`.
- **Erros de consumer:** `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → tópico `<topico>-dlq`. A DLQ de consumer é **independente** do `outbox.status = FALHA` (D-R4).
- **Commits:** Conventional Commits PT-BR, lowercase, sem acento no assunto. Um commit = uma mudança lógica.
- **Coordenação de migrations (importante):** a numeração precisa ser combinada para não colidir entre tracks do mesmo serviço.
  - Assinatura termina em `V6`. BE-8 cria `V7` (tabela `renovacao` + colunas de ciclo em `assinatura`). BE-10 cria `V8` (tabela de dedup `renovacao_evento_processado`). BE-7 não cria migration.
  - Pagamento termina em `V3`. BE-11 cria `V4` (`pagamento_renovacao` + `tentativa_cobranca`). BE-13 cria `V5` (dedup de `eventId` de webhook de renovação).
- **Verificação mínima antes de PR:** `make verify` verde no serviço alterado.

---

## 4. Gates sequenciais (bloqueantes)

- **Gate 0 — Contratos de renovação travados.** Criar `docs/contratos/contrato-eventos-renovacao.puml` espelhando a tabela do §2 (mesmo formato do `contrato-eventos-kafka.puml`). **Desbloqueia todas as tracks BE-7…BE-13.** É um doc, não código; pode ser feito imediatamente.
- Não há mais gate do 0.2.0: o webhook de ativação já está em `develop`.

---

## 5. Tracks (executáveis independentemente)

### BE-7 — Outbox roteia por `eventType` (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** Gate 0
- **Risco:** toca o `OutboxPublisher` que toda publicação usa. Mudança isolada e testável.
- **Consome:** design `docs/adesao/outbox-assinatura-solicitada.puml` e `docs/adesao/outbox-modelo-dados.puml`.
- **Definition of Done:**
  - O `OutboxPublisher` resolve o tópico por `eventType` (mapa configurável `eventType → tópico`), em vez do tópico fixo `assinatura-solicitada` (`OutboxPublisher.java:77`).
  - O mapa cobre os eventos existentes (`AssinaturaSolicitada → assinatura-solicitada`) e os novos (`RenovacaoSolicitada → renovacao-solicitada`, `AssinaturaRenovada/AssinaturaSuspensa → seus tópicos`).
  - A migration e o schema da `outbox` não mudam (o `eventType` já é coluna). Nenhuma mudança de comportamento para o fluxo de adesão existente.
  - `NewTopics` declara os tópicos novos do lado Assinatura (`renovacao-solicitada`).
- **Arquivos previstos:** `OutboxPublisher`, `MessagingConfig` (mapa de tópicos / propriedades), `KafkaTopicsConfig` (beans `NewTopic`).
- **Verificação:** o teste existente de publicação de `AssinaturaSolicitada` segue verde (rotas para `assinatura-solicitada`); um teste injeta um evento com `eventType = RenovacaoSolicitada` e afirma que foi para `renovacao-solicitada`.
- **Só trava:** BE-9 (que publica `RenovacaoSolicitada`). Não trava BE-8 (domínio) nem BE-10 (consumer).

### BE-8 — Domínio de ciclo + agregado `Renovacao` + migration (Assinatura) — fundação
- **Serviço:** Assinatura
- **Depende de:** Gate 0
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 26-72, 165-195).
- **Definition of Done:**
  - Migration `V7`: adiciona em `assinatura` as colunas `inicio_ciclo`, `fim_ciclo`, `proxima_renovacao_em` (DATE/TIMESTAMPTZ), `renovacao_automatica` (BOOLEAN default true). Cria a tabela `renovacao` com `UNIQUE (assinatura_id, ciclo_referencia)`.
  - `StatusAssinatura` ganha `EM_RENOVACAO`, `SUSPENSA`, `CANCELADA`.
  - Entidade `Renovacao` (status: `PENDENTE`, `APROVADA`, `TENTATIVAS_ESGOTADA`) com factory e transições de domínio.
  - Métodos em `Assinatura`: `renovar(novoFimCiclo)` (partindo de `ATIVA`/`EM_RENOVACAO`, rola `inicioCiclo = fimCiclo anterior`, recalcula `fimCiclo` e `proximaRenovacaoEm`) e `suspender()` (→ `SUSPENSA`). `ativar()` existente **não** muda.
- **Arquivos previstos:** `V7__*.sql`, `Assinatura` (campos + métodos), `StatusAssinatura`, `Renovacao`, `StatusRenovacao`, `RenovaturaRepository`.
- **Verificação:** testes de domínio afirmam transições válidas e rejeições (`ativar` a partir de `ATIVA` ainda é no-op; `renovar` define o novo ciclo; `suspender` só parte de `EM_RENOVACAO`).
- **Paralelizável com:** BE-7, BE-11. **Desbloqueia:** BE-9, BE-10.

### BE-9 — Scheduler de vencimento: cria `Renovacao` + outbox `RenovacaoSolicitada` (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-8 (domínio), BE-7 (publicação)
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 23-72) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 34-59).
- **Definition of Done:**
  - `@Scheduled` que seleciona assinaturas com `proxima_renovacao_em <= now()` **`FOR UPDATE SKIP LOCKED`** (recupera renovações atrasadas após indisponibilidade; impede duas instâncias processarem a mesma).
  - Para cada uma: se `renovacao_automatica = false` → `CANCELADA`. Se `true` → `INSERT renovacao (...) ON CONFLICT (assinatura_id, ciclo_referencia) DO NOTHING`. Se inseriu: `assinatura.status = EM_RENOVACAO` e grava `RenovacaoSolicitada` na outbox na **mesma transação**.
  - Se a renovação já existia (nenhuma linha inserida), não faz nada (idempotente por ciclo).
- **Arquivos previstos:** `RenovacaoScheduler`, query no repositório (`buscarVencidas(...)`), gravação na outbox (reusa `OutboxEvent.criar`).
- **Verificação:** dada uma assinatura `ATIVA` com `proximaRenovacaoEm` no passado, o scheduler cria a `renovacao`, move para `EM_RENOVACAO` e deixa um evento `RenovacaoSolicitada` `PENDENTE` na outbox; segunda execução não duplica.
- **Paralelizável com:** BE-10, BE-11, BE-12, BE-13. Contenção de domínio com BE-10 → ver §6.

### BE-10 — Consumer de resultado da renovação: renova ou suspende (Assinatura)
- **Serviço:** Assinatura
- **Depende de:** BE-8 (domínio), Gate 0 (contrato de resultado)
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 165-195) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 203-224).
- **Definition of Done:**
  - `@KafkaListener(topico = "renovacao-resultado", groupId = "assinatura")` desserializa `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas` (dispatch interno por tipo, ambos no mesmo tópico, D-R3).
  - Sob lock pessimista + dedup de `eventId` (migration `V8`, tabela `renovacao_evento_processado`): `Aprovado` → `renovacao.status = APROVADA`, `assinatura.renovar(...)` (→ `ATIVA` com novo ciclo) + outbox `AssinaturaRenovada`. `Esgotado` → `renovacao.status = TENTATIVAS_ESGOTADA`, `assinatura.suspender()` + outbox `AssinaturaSuspensa`.
  - `DefaultErrorHandler` → `renovacao-resultado-dlq`.
- **Arquivos previstos:** `RenovacaoResultadoConsumer`, handlers, `V8__*.sql` (dedup), gravação na outbox.
- **Verificação:** injetar `PagamentoRenovacaoAprovado` sintético e afirmar novo ciclo + `ATIVA` + outbox; injetar `RenovacaoTentativasEsgotadas` e afirmar `SUSPENSA`; redelivery do `eventId` não reaplica.
- **Paralelizável com:** BE-9, BE-11, BE-12, BE-13. Contenção de domínio com BE-9 → ver §6.

### BE-11 — Agregado pagamento-renovação + consumer `RenovacaoSolicitada` (Pagamento) — fundação
- **Serviço:** Pagamento
- **Depende de:** Gate 0
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 78-87) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 96-121).
- **Definition of Done:**
  - Migration `V4`: `pagamento_renovacao` (`UNIQUE (renovacao_id)`, idempotência por renovação) e `tentativa_cobranca` (`renovacao_id`, `numero` int, `status`, `proxima_tentativa_em`, `payment_id`).
  - `@KafkaListener(topico = "renovacao-solicitada", groupId = "pagamento")` desserializa `RenovacaoSolicitada`. Sob `ON CONFLICT (renovacao_id) DO NOTHING`: cria o pagamento de renovação e a tentativa número 1 (`status = PENDENTE`). Se já existia, conclui (idempotente por `renovacaoId`).
  - `NewTopics` declara `renovacao-resultado` e `renovacao-resultado-dlq`.
  - `DefaultErrorHandler` → `renovacao-solicitada-dlq`.
- **Arquivos previstos:** `PagamentoRenovacao`, `StatusPagamentoRenovacao`, `TentativaCobranca`, `StatusTentativa`, repositories, `V4__*.sql`, `RenovacaoSolicitada` (event), `RenovacaoSolicitadaConsumer`, `KafkaTopicsConfig`.
- **Verificação:** injetar `RenovacaoSolicitada` e afirmar pagamento + tentativa 1 criados; redelivery não duplica.
- **Paralelizável com:** BE-7, BE-8, BE-9, BE-10 (serviço distinto). **Desbloqueia:** BE-12, BE-13.

### BE-12 — Scheduler de tentativas de cobrança: chama o gateway (Pagamento)
- **Serviço:** Pagamento
- **Depende de:** BE-11
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 89-163) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 131-201).
- **Definition of Done:**
  - `@Scheduled` que busca `tentativa_cobranca` vencida (`proxima_tentativa_em <= now()`).
  - Chama `POST /v1/payments` (`Idempotency-Key = renovacaoId:tentativa`, `externalReference = renovacaoId`, WebClient existente, D-R2). Retry técnico curto reusa a mesma chave e **não** incrementa o número da tentativa.
  - Após aceite do gateway (`PENDING`), aguarda o webhook decidir (BE-13). O scheduler não decide sozinho `APPROVED`/`REJECTED`; ele cria a tentativa no gateway.
  - Terminal de negócio (3 recusas) só é atingido pelo caminho do webhook (BE-13), que cria a próxima tentativa ou esgota. **Não** há suspensão por falha técnica (D-R4).
- **Arquivos previstos:** `CobrancaRenovacaoScheduler`, reuso de `GatewayPagamentoClient` (ou método novo que recebe `renovacaoId`/`tentativa`/`valor`), atualização de `TentativaCobranca`.
- **Verificação:** dada uma tentativa vencida, o scheduler chama o gateway com a `Idempotency-Key` esperada; falha técnica não consome tentativa.
- **Paralelizável com:** BE-9, BE-10, BE-13. Contenção de domínio com BE-13 → ver §6.

### BE-13 — Webhook de renovação: consulta gateway, decide, publica resultado (Pagamento)
- **Serviço:** Pagamento
- **Depende de:** BE-11
- **Consome:** design `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 113-163) e `docs/renovacao/renovacao-automatica-retry-dlq.puml` (linhas 154-201).
- **Definition of Done:**
  - Estende o endpoint `POST /webhooks/payments` existente (do 0.2.0, `WebhookPagamentoController`) para despachar conforme o `externalReference`: se for uma `renovacaoId` conhecida, segue o fluxo de renovação; senão, segue o fluxo de ativação.
  - Reusa a validação HMAC, a consulta de status (`GET /v1/payments/{paymentId}`), a normalização e o **publish-then-ACK** já implementados (`ProcessarWebhookPagamento`). O `eventId` só é registrado após o publish confirmado.
  - `APPROVED` → marca tentativa `APROVADA`, publica `PagamentoRenovacaoAprovado` (encerra as tentativas). `REJECTED` e tentativa < 3 → marca `RECUSADA`, cria próxima tentativa com `proxima_tentativa_em` (durável, pega pelo scheduler BE-12). `REJECTED` e tentativa = 3 → marca `TENTATIVAS_ESGOTADAS`, publica `RenovacaoTentativasEsgotadas`. `PENDING` → não consome tentativa, aguarda novo webhook.
  - Migration `V5`: dedup de `eventId` de webhook de renovação (pode estender a `webhook_evento_processado` existente, sem nova tabela).
- **Arquivos previstos:** `WebhookPagamentoController`/`ProcessarWebhookPagamento` (ramo de renovação), `V5__*.sql`, eventos `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas`.
- **Verificação:** simular `APPROVED`/`REJECTED` (3x) no mock → webhook publica o resultado correto; redelivery do `eventId` não republica; `PENDING` não consome tentativa.
- **Paralelizável com:** BE-9, BE-10, BE-12. Contenção de domínio com BE-12 → ver §6.

---

## 6. Mapa de paralelismo e sequenciamento

```
Gate 0: contratos renovação travados (doc)
   │
   ├──────── STREAM ASSINATURA ──────────────────────┐
   │                                                  │
   │   BE-7: outbox roteia por eventType              │
   │   BE-8: domínio ciclo + Renovacao + V7 (fnd)     │
   │     ├──> BE-9: scheduler vencimento              │
   │     │      (depende tb de BE-7 p/ publicar)      │
   │     └──> BE-10: consumer resultado (V8)          │
   │                                                  │
   ├──────── STREAM PAGAMENTO ────────────────────────┐
   │                                                  │
   │   BE-11: agregado pagto-renov + consumer (V4,fnd)│
   │     ├──> BE-12: scheduler tentativas cobrança    │
   │     └──> BE-13: webhook renovação (V5)           │
   │                                                  │
   └────────────────────────┬─────────────────────────┘
                            ▼
              Convergência: E2E renovação + release/0.3.0
```

- **Concorrência máxima:** 2 streams. Após as fundações (`BE-8`, `BE-11`), até 4 tracks em paralelo: `BE-9`, `BE-10`, `BE-12`, `BE-13`.
- **Dois streams sem contenção de código:** Stream Assinatura (BE-7, BE-8, BE-9, BE-10) e Stream Pagamento (BE-11, BE-12, BE-13). Cross-stream só pelo contrato (Gate 0), nunca pelo código.
- **BE-7 só trava quem publica:** ela libera BE-9 (que publica `RenovacaoSolicitada`), mas não bloqueia BE-8 (domínio puro) nem BE-10 (consumer puro). Por isso abre paralelo com BE-8 desde o Gate 0.
- **Contenção dentro do Stream Assinatura:** `BE-9` (cria `Renovacao`) e `BE-10` (atualiza `Renovacao`) mexem no mesmo agregado. Sugerir **merge de BE-9 antes de BE-10** (mesma recomendação que o 0.2.0 fez para A antes de B no domínio `Assinatura`).
- **Contenção dentro do Stream Pagamento:** `BE-12` (escreve `TentativaCobranca`) e `BE-13` (atualiza `TentativaCobranca`) compartilham o agregado. Sugerir **merge de BE-12 antes de BE-13**, ou coordenar a migração `V5` (dedup).
- **Long pole:** Stream Assinatura (`BE-8` fundação + `BE-9`/`BE-10`); Stream Pagamento é sequencial (`BE-11` → `BE-12`/`BE-13`) mas cada peça é leve. Sem mais bloqueios externos, o caminho crítico é só o contrato + as fundações.

---

## 7. Decisões abertas (defaults assumidos — confirmar ou ajustar)

| ID | Decisão | Default |
|----|---------|---------|
| O-R1 | Tópico de resultado único vs. separado | Um tópico `renovacao-resultado` com dois `eventType` (D-R3). Consumer faz dispatch interno. Alternativa: dois tópicos `renovacao-aprovada`/`renovacao-esgotada`. |
| O-R2 | Default de `renovacao_automatica` | `true` (opt-out). Confirmar com produto. |
| O-R3 | Janela e backoff das tentativas de cobrança | 3 tentativas com `proxima_tentativa_em` configurável (ex.: D+1, D+3, D+7). Backoff técnico curto reutiliza a mesma `Idempotency-Key`. |
| O-R4 | Reconciliação de cobranças `PENDING` abandonadas | Fora de escopo (igual ao 0.2.0). Roadmap futuro. |
| O-R5 | Cancelamento explícito pelo cliente | A renovação trata só `renovacao_automatica = false` → `CANCELADA` no vencimento. Um endpoint de cancelamento ad hoc fica para roadmap futuro. |
| O-R6 | Bump de versão 0.2.0 nos poms | Os poms ainda estão em `0.1.0` nos dois serviços. Antes do `release/0.3.0`, fechar o `release/0.2.0` (bump + CHANGELOG), caso ainda não tenha sido feito. |

---

## 8. Convergência e definition of done (release 0.3.0)

1. Mergear BE-7, BE-8, BE-9, BE-10, BE-11, BE-12, BE-13 → `develop`.
2. Garantir que o `0.2.0` foi releaseado (bump em ambos os `pom.xml` + `CHANGELOG.md`), pois o `0.3.0` assume o webhook de ativação em produção.
3. **Fluxo E2E de renovação:** assinatura `ATIVA` com `proximaRenovacaoEm` no passado → scheduler cria `Renovacao` + outbox `RenovacaoSolicitada` → outbox publica → Pagamento cria pagamento de renovação + tentativa 1 → scheduler chama gateway (`Idempotency-Key = renovacaoId:1`) → simular `APPROVED` no mock → webhook publica `PagamentoRenovacaoAprovado` → Assinatura rola o ciclo e volta para `ATIVA`. Caminho de falha: 3 recusas → `RenovacaoTentativasEsgotadas` → Assinatura `SUSPENSA`.
4. `release/0.3.0`: bump `0.3.0` em `services/assinatura/pom.xml` e `services/pagamento/pom.xml`; atualizar `CHANGELOG.md`.

---

## 9. Notas para os agentes (handoff)

- **Leia antes:** este doc, o roadmap de renovação, os dois `.puml` de renovação, o `contrato-eventos-kafka.puml` (estilo de contrato), o `AGENTS.md` (estilo/testes/commits).
- **Pegue UMA track.** Codifique contra o contrato (§2), não contra a implementação do outro serviço.
- **TDD:** escreva o teste que reproduz o comportamento antes do código de produção (`AGENTS.md` §Scientific TDD).
- **Verifique:** `make verify` no serviço alterado. Integração (`@Tag("integration")`) só no fim, via `make test-integration`.
- **Não mexa** no outro serviço nem nos contratos travados (§2) sem bump de versão.
- **Commits pequenos**, um por mudança lógica; `make lint` roda no pre-commit.
