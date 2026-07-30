# Plano de implementação paralela — fluxo de assinatura 0.2.0

**Versão alvo:** `0.2.0`
**Branch base:** `develop`
**Data:** 2026-07-30 (revisado)
**Contrato de referência:** `docs/contrato-eventos-kafka.puml`
**Alinhado com:** `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md` (BE-2…BE-6, FF-1). Detalha as tracks para execução por múltiplos agentes.

> **Revisão (2026-07-30):** a colocação da outbox foi invertida pela semântica transacional (§1 D1); o Pagamento Service é **stateful** com DB próprio (§1 D8).

> **Como usar:** cada track em §5 é autocontida. Um agent lê este doc + `docs/contrato-eventos-kafka.puml` + os `.puml` citados + `AGENTS.md`, escolhe UMA track, implementa com TDD e verifica com `make verify` antes do merge.

---

## 1. Decisões de arquitetura (travadas)

| ID | Decisão | Racional |
|----|---------|----------|
| D1 | **Outbox no Assinatura Service** (publica `AssinaturaSolicitada`) | `INSERT assinatura` + publicação é dual-write **sem arranjo de ordem**: `publish→commit` = cobrança fantasma no gateway; `commit→publish` = assinatura trava em `AGUARDANDO_PAGAMENTO` sem auto-cura (retry devolve 409). A outbox os torna atômicos. |
| D2 | **`PagamentoStatusAtualizado` por publish-then-ACK direto** (sem outbox) | Só se ACK 200 ao mock após o Kafka confirmar; falha → não-200 → o mock reenvia o webhook. Consumer dedup por `eventId`. Sem perda, sem outbox no Pagamento. |
| D3 | Contratos travados em `docs/contrato-eventos-kafka.puml` | É o "interruptor" do paralelismo: cada serviço implementa contra o contrato. |
| D4 | `status` **normalizado** `{APPROVED, REJECTED, PENDING}` | O gateway também emite `CANCELLED`/`EXPIRED`, mapeados para `REJECTED` antes de publicar. |
| D5 | `valor` em **BigDecimal (reais)**, ex. `39.90`, ponta a ponta | Sem conversão para centavos: o `valor` do evento é enviado direto ao gateway (mock em `float64`). Simplifica o consumer. |
| D6 | `eventId` do evento = `eventId` do webhook | Dedup ponta-a-ponta; todo consumer deduplica por `eventId` (entrega at-least-once). |
| D7 | **DTOs duplicados por serviço** (sem módulo compartilhado) | Poms independentes, serialização String/JSON. O diagrama é a fonte da verdade. |
| D8 | **Pagamento Service é stateful** (DB próprio) | Persiste correlação `assinatura_uuid ↔ paymentId` (idempotência + auditoria) e webhooks processados (dedup de `eventId`). Padrão para serviço de pagamento. O publish-then-ACK (D2) permanece, **sem outbox**. |

---

## 2. Contratos compartilhados (o lock — não mexer sem bump de versão)

| Evento | Tópico | Key | Produtor | Consumidor | Garantia / mecanismo |
|--------|--------|-----|----------|-----------|----------------------|
| `AssinaturaSolicitada` | `assinatura-solicitada` | `assinaturaId` | Assinatura (**outbox**) | Pagamento | transacional (at-least-once) |
| `PagamentoStatusAtualizado` | `pagamento-status-atualizado` | `assinaturaId` | Pagamento (**publish-then-ACK**) | Assinatura | retry do mock + dedup consumer |

- **groupIds:** Assinatura consome com `group-id: assinatura`; Pagamento consome com `group-id: pagamento` (já configurados).
- **Serialização:** String (JSON). Jackson nos DTOs.
- **DTOs:** records Java, campos e tipos exatos do `contrato-eventos-kafka.puml`. Pacote `<servico>.messaging.event`.
- **Tópicos:** declarar via bean `NewTopics` (KafkaAdmin) no serviço **produtor**, para determinismo (não depender de auto-create).

---

## 3. Convenções (aplicar em toda track)

- **Branches:** `feat/<nome>` a partir de `develop`, kebab-case.
- **Estilo:** Google Java Style; `make format` corrige, `make lint` verifica. Javadoc obrigatório em todo público.
- **Testes:** JUnit 5 + AssertJ, `@DisplayName`, mensagens `.as(...)`. Excluir `@Tag("integration")` do `make verify`.
- **Erros de consumer:** `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` → tópico `<topico>-dlq` (DLQ de consumer é **independente** da outbox).
- **Commits:** Conventional Commits PT-BR, lowercase, sem acento no assunto. Um commit = uma mudança lógica.
- **Verificação mínima antes de PR:** `make verify` verde no serviço alterado.

---

## 4. Gates sequenciais (bloqueantes)

- **Gate 0 — Fechar BE-2.** Merge `feat/solicita-assinatura-sem-fila` → `develop`. Único desbloqueador: tudo usa a entidade `Assinatura` + o fluxo de solicitação. **Em andamento.**
- **Gate 1 — Contratos travados.** ✅ DONE (`docs/contrato-eventos-kafka.puml`).
- A partir do Gate 0, as tracks §5 abrem.

---

## 5. Tracks (executáveis independentemente)

### Track A — Outbox + publica `AssinaturaSolicitada` (Assinatura) — **track pesada**
- **Branch:** `feat/outbox-assinatura-solicitada`
- **Serviço:** Assinatura
- **Depende de:** Gate 0 (BE-2)
- **Consome:** contrato `AssinaturaSolicitada`; design `docs/outbox-assinatura-solicitada.puml` e `docs/outbox-modelo-dados.puml`
- **Definition of Done:**
  - Migration cria a tabela `outbox` (schema do `outbox-modelo-dados.puml`) + índice de polling `(status, proxima_tentativa_em, criado_em) WHERE status = 'PENDENTE'`.
  - `AssinaturaService` grava a outbox na **mesma transação** do `INSERT` da assinatura (evento `PENDENTE`).
  - `OutboxPublisher` (`@Scheduled`): `SELECT ... FOR UPDATE SKIP LOCKED`, publica `AssinaturaSolicitada` (key = `assinaturaId`), marca `PUBLICADO`; 3 tentativas + backoff + jitter; `FALHA` como DLQ persistida.
  - DTO `AssinaturaSolicitada` record + bean `NewTopics` declara `assinatura-solicitada`.
- **Arquivos previstos:** migration outbox, `Outbox` (entidade/repo), `OutboxPublisher`, `AssinaturaService` (grava outbox), `AssinaturaSolicitada` (event), `KafkaTopicsConfig`.
- **Verificação:** `POST /assinaturas` cria linha na outbox na mesma tx; publisher publica no Kafka (`spring-kafka-test`); falha de publish → retry → `FALHA` após 3.
- **Paralelizável com:** B, C, P-0.

### Track B — Consome `PagamentoStatusAtualizado`
- **Branch:** `feat/consome-pagamento-status`
- **Serviço:** Assinatura
- **Depende de:** Gate 0 (BE-2)
- **Consome:** contrato `PagamentoStatusAtualizado`
- **Definition of Done:**
  - `@KafkaListener(topico = "pagamento-status-atualizado", groupId = "assinatura")` desserializa JSON.
  - Sob lock pessimista: `APPROVED`→`ATIVA` (define `dataInicio`/`dataExpiracao`); `REJECTED`→`PAGAMENTO_RECUSADO`; `PENDING`→sem alteração.
  - Dedup por `eventId`.
  - `DefaultErrorHandler` → `pagamento-status-atualizado-dlq`.
- **Arquivos previstos:** `PagamentoStatusAtualizado` (event), `PagamentoStatusConsumer`, transição de status (método no domínio `Assinatura`), config de erro/DLQ.
- **Verificação:** injetar evento sintético (`APPROVED`/`REJECTED`/`PENDING`/duplicado) e afirmar transições. Não depende do Pagamento Service.
- **Paralelizável com:** A, C, P-0, D.

### Track C — Login de cliente (FF-1)
- **Status:** Concluído (PR #4 merged em `develop` em 2026-07-30). Cadastro cria `User` com `ROLE_CLIENT` na mesma transação; login de cliente emite JWT com `subject=email` e `role=ROLE_CLIENT`; credenciais inválidas devolvem `401`. Migration `V4__adiciona_usuario_id_em_users.sql` (renumerada de V3 após o BE-2 consumir V3). Dívida restante: `JwtAuthenticationFilter` ignora o claim `role` (só relevante no follow-up que exigir auth nos endpoints de assinatura).
- **Branch:** `feat/login-cliente`
- **Serviço:** Assinatura
- **Depende de:** Gate 0 (BE-2). Fecha lacuna do ADR 0001.
- **Definition of Done:**
  - Cadastro (`POST /usuarios`) cria também um `User` auth (senha) ligado ao `Usuario` de domínio.
  - `/auth/login` funciona para clientes.
- **Arquivos previstos:** `UsuarioService`/`UsuarioController` (criar `User`), `User`/`Usuario` (vínculo), `SecurityConfig` (rotas).
- **Verificação:** teste de cadastro + login emite JWT válido.
- **Paralelizável com:** A, B, P-0, D.
- **Restrição de ordem:** deve ser mergeado **antes** do fluxo end-to-end acionar o gateway com transações reais (convergência §8).

### Track P-0 — DB do Pagamento Service (fundação)
- **Branch:** `feat/pagamento-db`
- **Serviço:** Pagamento
- **Depende de:** Gate 0
- **Definition of Done:**
  - Adiciona `data-jpa`, `flyway`, `flyway-database-postgresql`, `postgresql` (driver) ao `pom.xml` do Pagamento.
  - `application.yaml` (+`application-dev`) com datasource apontando para um banco `pagamento`.
  - `docker-compose.yml`: segundo banco `pagamento` no mesmo container Postgres (init script `POSTGRES_MULTIPLE_DATABASES`) + `depends_on: postgres` + env de datasource no serviço `pagamento`.
  - Migration base `V1__init.sql`.
- **Arquivos previstos:** `pom.xml`, `application.yaml`/`application-dev.yaml`, `docker-compose.yml`, `docker/postgres/init-multiple-dbs.sh`, `db/migration/V1__init.sql`.
- **Verificação:** `make verify` verde; app sobe no compose e conecta ao banco.
- **Paralelizável com:** A, B, C. **Bloqueia** D e E.

### Track D — Consome `AssinaturaSolicitada` + cria cobrança (Pagamento, stateful)
- **Branch:** `feat/pagamento-cria-cobranca`
- **Serviço:** Pagamento
- **Depende de:** P-0, contrato `AssinaturaSolicitada`, contrato do gateway (`docs/mock-meio-pagamento.puml`); design `docs/pagamento-cria-cobranca-sequencia.puml` e `pagamento-cria-cobranca-processo.puml`
- **Definition of Done:**
  - `@KafkaListener(topico = "assinatura-solicitada", groupId = "pagamento")` desserializa JSON.
  - Busca correlação por `assinaturaId`; se existir, conclui (idempotente). Se não, chama `POST /v1/payments` (`Idempotency-Key = assinaturaId`, WebClient) e persiste correlação `assinaturaId ↔ paymentId` com `status = PENDING` (persistência idempotente por `assinaturaId`).
  - `DefaultErrorHandler` → `assinatura-solicitada-dlq` (até 3 tentativas com jitter).
- **Arquivos previstos:** `AssinaturaSolicitada` (event), `AssinaturaSolicitadaConsumer`, `GatewayPagamentoClient` (WebClient), entidade/repo de cobrança (correlação), migration da tabela, config de erro/DLQ.
- **Verificação:** injetar `AssinaturaSolicitada` com gateway em WireMock → afirmar `POST /v1/payments` com `Idempotency-Key` + correlação persistida; redelivery não duplica (busca correlação).
- **Paralelizável com:** A, B, C (serviços distintos). **Bloqueia** E.

### Track E — Webhook + publica `PagamentoStatusAtualizado` (publish-then-ACK, stateful)
- **Branch:** `feat/webhook-pagamento`
- **Serviço:** Pagamento
- **Depende de:** D, P-0, contrato `PagamentoStatusAtualizado`, contrato do webhook (`docs/mock-meio-pagamento.puml`)
- **Definition of Done:**
  - `POST /webhooks/payments`: valida HMAC (`X-Mock-Signature`), consulta status oficial (`GET /v1/payments/{paymentId}`), **normaliza** status (D4) e publica `PagamentoStatusAtualizado` (key = `assinaturaId`).
  - **publish-then-ACK**: retorna `200` só após o Kafka ackar; falha → não-200 → o mock reenvia o webhook.
  - Dedup de `eventId` (`X-Mock-Event-Id`): registra o eventId **após** o publish bem-sucedido (nunca antes), para evitar skip pós-crash.
  - Bean `NewTopics` declara `pagamento-status-atualizado`.
- **Arquivos previstos:** `PagamentoStatusAtualizado` (event), `WebhookPagamentoController`, validação HMAC, normalização de status, entidade/repo de webhooks processados (dedup), `KafkaTopicsConfig`.
- **Verificação:** simular `APPROVED`/`REJECTED`/`CANCELLED`/`EXPIRED` no mock → webhook → mensagem no Kafka com status normalizado; falha de publish → não-200; replay do mesmo `eventId` não republica.
- **Paralelizável com:** A, B, C (mas **após** D no mesmo serviço).

---

## 6. Mapa de paralelismo e sequenciamento

```
Gate 0: BE-2 → develop          Gate 1: contratos ✅
               │
   ┌───────────┼────────┬────────────┐
   ▼           ▼        ▼            ▼
 Track A    Track B   Track C      P-0 (PG DB)
(AS outbox)(AS cons) (AS login)      │
   │           │        │            ▼
   └─────┬─────┴────────┘       Track D (PG consome + gateway + correlação)
         │                          │
         ▼                          ▼
  (merge Assinatura)          Track E (PG webhook + publish-then-ACK)
         │                          │
         └────────────┬─────────────┘
                      ▼
           Convergência: E2E + release/0.2.0
```

- **Concorrência máxima pós-Gate 0:** 4 agents — A, B, C (Assinatura) + P-0 (Pagamento). Depois D (após P-0), depois E (após D).
- **Dois streams** sem contenção de código: Stream Assinatura (A pesada, B, C) e Stream Pagamento (P-0 → D → E).
- **Long pole no Assinatura:** Track A (outbox). Pagamento é sequencial (P-0 → D → E) mas cada peça é leve.
- **Ordem de merge no Assinatura:** A e B podem tocar o domínio `Assinatura`; sugere A antes de B. C é ortogonal (auth).

---

## 7. Decisões abertas (defaults assumidos — confirmar ou ajustar)

| ID | Decisão | Default |
|----|---------|---------|
| O2 | DLQ de consumer | dead-letter topic `<topico>-dlq` via `DeadLetterPublishingRecoverer`. |
| O3 | Banco do Pagamento | segundo DB `pagamento` no mesmo container Postgres (init script). |
| O4 | Stack de persistência do Pagamento | `data-jpa` + `flyway` + driver postgres (igual ao Assinatura). |
| O5 | Auto-create de tópicos | declarar via `NewTopics`; auto-create aceitável por ora. |

> O estado travado do Assinatura (publish pós-commit perdido) ficou **resolvido pela outbox** (D1) — sem track extra.

---

## 8. Convergência e definition of done (release 0.2.0)

1. Mergear A, B, C, P-0, D, E → `develop` (C **antes** do E2E).
2. **Fluxo E2E:** cadastra usuário → solicita assinatura (202) → outbox publica `AssinaturaSolicitada` → Pagamento cria cobrança no mock e persiste correlação → simula `APPROVED` no mock → webhook dispara → publish-then-ACK publica `PagamentoStatusAtualizado` → Assinatura ativa → `GET /assinaturas/{uuid}` retorna `ATIVA`.
3. `release/0.2.0`: bump `0.2.0` em `services/assinatura/pom.xml` e `services/pagamento/pom.xml`; atualizar `CHANGELOG.md`.

---

## 9. Notas para os agentes (handoff)

- **Leia antes:** este doc, `docs/contrato-eventos-kafka.puml`, os `.puml` citados na sua track, `AGENTS.md` (estilo/testes/commits).
- **Pegue UMA track.** Codifique contra o contrato, não contra a implementação do outro serviço.
- **TDD:** escreva o teste que reproduz o comportamento antes do código de produção (`AGENTS.md` §Scientific TDD).
- **Verifique:** `make verify` no serviço alterado. Integração (`@Tag("integration")`) só no fim.
- **Não mexa** no outro serviço nem em contratos travados (§2) sem bump de versão.
- **Commits pequenos**, um por mudança lógica; `make lint` roda no pre-commit.
