# Fluxos do sistema de assinaturas

Visão consolidada dos fluxos ponta a ponta em texto (ASCII), para referência
rápida e como base para diagramas em ferramentas externas. Os nomes de
componentes são as classes reais em `develop` (HEAD `5a41ebd`), organizadas
por capacidade (`com.globo.assinatura.*` / `com.globo.pagamento.*`).

## Legenda de cores (por serviço)

| Serviço | Cor sugerida |
|---|---|
| `assinatura` | azul |
| `pagamento` | verde |
| `mock` (gateway) | cinza/laranja |
| `kafka` / `redis` (infra) | roxo / vermelho |

---

## FLUXO 1 — Criação de assinatura (adesão)

```
assinatura  ──AssinaturaSolicitada──▶  kafka  ──▶  pagamento  ──POST /v1/payments──▶  mock
     ▲                                                                              │
     │                                                                              ▼ (async)
     └──────PagamentoStatusAtualizado───── kafka ◀─── webhook ◀─── POST /webhooks ◀──┘
```

| Etapa | Serviço | Componente |
|---|---|---|
| `POST /assinaturas` → 202, índice único parcial (409) | assinatura | `adesao.api.AdesaoController` → `adesao.SolicitarAssinatura` |
| Grava `AssinaturaSolicitada` na outbox (mesma tx) | assinatura | `adesao.SolicitarAssinatura` + `shared.outbox.OutboxPublisher` |
| Publisher outbox → tópico `assinatura-solicitada` | assinatura | `shared.outbox.OutboxPublisher` (rota via `RotasEventoTopicoProperties`) |
| Consumer cria cobrança, `Idempotency-Key=assinaturaId` | pagamento | `adesao.evento.AssinaturaSolicitadaConsumer` → `adesao.CriarCobrancaAdesao` |
| HTTP `POST /v1/payments` com retry/backoff transitório | pagamento | `gateway.GatewayPagamentoClient` + `gateway.GatewayRetryPolicy` |
| Webhook HMAC + dedup `eventId` + GET status oficial | pagamento | `webhook.ProcessarWebhookPagamento` (`HmacSignatureValidator`, `WebhookEventoProcessado`) |
| Publica `PagamentoStatusAtualizado` via outbox | pagamento | `webhook.RegistrarResultadoWebhook` + `OutboxPublisher` |
| Ativa/recusa sob lock pessimista, dedup `eventId` | assinatura | `adesao.ConfirmarPagamentoAdesao` (`PagamentoEventoProcessado`) |

---

## FLUXO 2 — Renovação de assinatura

```
assinatura  ──RenovacaoSolicitada──▶  kafka  ──▶  pagamento  ──POST /v1/payments──▶  mock
     ▲                                                                              │
     │                                                                              ▼ (async)
     └──────renovacao-resultado──── kafka ◀─── webhook ◀─── POST /webhooks ◀────────┘
```

| Etapa | Serviço | Componente |
|---|---|---|
| Scheduler varre vencimentos por instante exato, `SKIP LOCKED` | assinatura | `renovacao.agendador.RenovacaoScheduler` |
| `EM_RENOVACAO` entra no índice único de aberta | assinatura | `adesao.SolicitarAssinatura` (`Assinatura.STATUS_ABERTOS`) |
| Grava `RenovacaoSolicitada` na outbox | assinatura | `RenovacaoScheduler` + `OutboxPublisher` |
| Consumer cria `PagamentoRenovacao` (idempotente) + 1ª tentativa | pagamento | `renovacao.evento.RenovacaoSolicitadaConsumer` → `renovacao.CriarPagamentoRenovacao` |
| Scheduler cobra tentativas prontas, `SKIP LOCKED` | pagamento | `renovacao.agendador.CobrancaRenovacaoScheduler` (`TentativaCobranca`) |
| Retry/backoff transitório no cliente HTTP | pagamento | `gateway.GatewayPagamentoClient` + `gateway.GatewayRetryPolicy`/`GatewayRetryProperties` |
| Teto de falhas técnicas consecutivas esgota renovação | pagamento | `CobrancaRenovacaoScheduler` + `TentativaCobranca.registrarFalhaTecnica` |
| Webhook decide: aprovado, recusa com backoff (1,3 dias), esgota | pagamento | `webhook.ProcessarWebhookRenovacao` |
| Resultado em `renovacao-resultado` (campo `tipo`) | pagamento→assinatura | outbox (`PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas`) |
| Aprovação rola ciclo / 3 recusas suspendem | assinatura | `renovacao.ProcessarRenovacaoResultado` (`RenovacaoEventoProcessado`) |

---

## FLUXO 3 — Webhook do gateway (adesão + renovação)

```
mock  ──POST /webhooks + HMAC──▶  pagamento  ──GET /v1/payments/{id}──▶  mock
                                     │
                                     ▼
                                outbox ──▶ kafka  (pagamento-status-atualizado OU renovacao-resultado)
```

| Etapa | Serviço | Componente |
|---|---|---|
| Despacho por `externalReference` (renovação vs adesão) | pagamento | `webhook.RegistrarResultadoWebhook` |
| HMAC + `eventId` + estado de domínio (idempotência tripla) | pagamento | `webhook.ProcessarWebhookPagamento` (`HmacSignatureValidator`, `WebhookEventoProcessado`) |
| Normalização de status do gateway | pagamento | `webhook.NormalizadorStatus` |
| Consulta status oficial (`GET /v1/payments/{id}`) | pagamento | `gateway.GatewayPagamentoClient.consultarStatus` |
| Mock reenvia 3x se 503, mesmo `eventId` | mock | `main.go dispatchWebhook` |

---

## FLUXO 4 — Cancelamento de assinatura

```
cliente  ──POST──▶  assinatura  ──CancelamentoAgendado / AssinaturaCancelada──▶  kafka  ──▶  pagamento
                                                                                              │
                                                                                              ▼
                                                                          cancela TentativaCobranca PENDENTES
```

| Etapa | Serviço | Componente |
|---|---|---|
| `POST /assinaturas/{uuid}/cancelamento` sob lock | assinatura | `cancelamento.api.CancelamentoController` → `cancelamento.CancelarAssinatura` |
| Ativa/em-renovação com auto → `AGENDADO` (fim do ciclo) | assinatura | `assinatura.Assinatura.solicitarCancelamento` (`CancelamentoAgendado`) |
| Demais status → `IMEDIATO` (status `CANCELADA`) | assinatura | `assinatura.Assinatura.solicitarCancelamento` (`AssinaturaCancelada`) |
| Consumer de `assinatura-cancelada` cancela tentativas | pagamento | `cancelamento.evento.CancelamentoConsumer` → `cancelamento.CancelarTentativasPendentes` (`CancelamentoEventoProcessado`) |

---

## FLUXO 5 — Consulta com cache (listagem + ativa)

```
cliente  ──GET──▶  assinatura  ──miss──▶  banco  ──popula──▶  redis
                       │                     ▲
                       └──────── hit ────────┘
```

| Etapa | Serviço | Componente |
|---|---|---|
| `GET /assinaturas` paginado (cache-aside, TTL 300s) | assinatura | `consulta.api.ListaAssinaturasController` → `consulta.ListarAssinaturas` + `consulta.AssinaturaListaCache` |
| `GET /assinaturas/ativa` (200 ou 404, cache de ausência, TTL 5min) | assinatura | `consulta.api.ConsultaAssinaturaAtivaController` → `consulta.ConsultarAssinaturaAtiva` + `consulta.AssinaturaAtivaCache` |
| `GET /assinaturas/{uuid}` (pontual, sem cache) | assinatura | `consulta.api.ConsultaAssinaturaController` → `consulta.ConsultarAssinatura` |
| Invalida por versão pós-commit (commands/consumers/scheduler) | assinatura | `shared.cache.CacheVersionado.invalidarAposCommit` |
| Degradação silenciosa (falha Redis vira miss) | assinatura | `shared.cache.CacheVersionado` |

---

## FLUXO 6 — Consulta de cobrança

```
cliente  ──GET /cobrancas/{assinaturaId}──▶  pagamento  ──▶  banco  ──▶  CobrancaResponse(paymentId, status)
```

| Etapa | Serviço | Componente |
|---|---|---|
| `GET /cobrancas/{assinaturaId}` → 200/404 | pagamento | `consulta.api.CobrancaController` → `consulta.ConsultarCobranca` (`CobrancaRepository`) |

Sem Kafka, sem cache.

---

## FLUXO 7 — Recuperação da outbox (automática + manual)

```
PENDENTE  ──publisher──▶  kafka
   │ falha 3x (RetryPolicy)
   ▼
  FALHA  ──[scheduler quarentena]──▶  RETENTATIVA_DLQ  ──publisher──▶  kafka
                                              │  ciclos < max (reloop)
                                       ciclos = max
                                              ▼
                                     FALHA terminal ──[operador]──▶  retoma (REST)
```

| Etapa | Serviço | Componente |
|---|---|---|
| Publisher drena `PENDENTE` (+`RETENTATIVA_DLQ`), `SKIP LOCKED` | assinatura + pagamento | `shared.outbox.OutboxPublisher` |
| Retry com backoff exponencial + jitter, 3 tentativas → `FALHA` | assinatura + pagamento | `shared.outbox.RetryPolicy` |
| `FALHA → RETENTATIVA_DLQ` após quarentena, limite de ciclos | assinatura + pagamento | `shared.outbox.OutboxRecuperacaoScheduler` |
| Lista eventos em `FALHA` via REST, sem SQL direto | assinatura + pagamento | `shared.outbox.api.OutboxFalhaController` + `shared.outbox.ListarFalhasOutbox` |
| Retoma evento terminal preservando idempotência | assinatura + pagamento | `shared.outbox.RetomarEventoOutbox` |
| JWT/segurança exigindo `app_jwt_secret` fora do dev | assinatura + pagamento | `shared.seguranca.JwtAuthenticationFilter`, `JwtService` |

---

## FLUXO 8 — Observabilidade de falhas

```
outbox (status=FALHA)  ──[scheduler métricas]──▶  /actuator/prometheus  ──▶  Prometheus/Grafana
tópicos *-dlq (retenção)
```

| Etapa | Serviço | Componente |
|---|---|---|
| Métrica de eventos em `FALHA` e volume retido em `*-dlq` | assinatura + pagamento | `shared.outbox.OutboxMetricasScheduler` |
| Contagem restrita aos status monitorados | assinatura + pagamento | `shared.outbox.OutboxRepository` |
| Métricas permanecem vivas mesmo em falha | assinatura + pagamento | `OutboxMetricasScheduler` |
| Endpoint Prometheus exposto | assinatura + pagamento | `application.yaml` + `shared.seguranca.SecurityConfig` |

---

## FLUXO 9 — Replay de DLQ de consumer

```
kafka (*-dlq)  ──[@KafkaListener replay, autoStartup off]──▶  republica no tópico original
                                                              (header kafka_dlt-original-topic)
```

| Etapa | Serviço | Componente |
|---|---|---|
| Listener de replay lê `*-dlq`, backoff longo (1h), republica | assinatura + pagamento | `shared.kafka.replay.ReplayDlqConsumer` (`ReplayDlqKafkaConfig`) |
| Config externalizada (`APP_KAFKA_REPLAY_AUTO_STARTUP`) | assinatura + pagamento | `shared.kafka.replay.ReplayDlqProperties` |
| Teto de bounces + validação do tópico original | assinatura + pagamento | `ReplayDlqConsumer` |
| Idempotência absorve reprocessamento | assinatura + pagamento | guards `eventId` + chave natural |

---

## FLUXO 10 — Config operacional dos DLTs e logging

```
kafka (partitions, replication, retention explícitos) ◀──[config admin]── assinatura + pagamento
logging alinhado em INFO/WARN/ERROR com chaves estruturadas
```

| Etapa | Serviço | Componente |
|---|---|---|
| Partitions/replicação/retenção explícitos nos tópicos DLQ | pagamento | `shared.kafka.KafkaTopicsConfig` |
| Idem (assinatura) | assinatura | `shared.kafka.MessagingConfig` |
| Logging de outbox alinhado em `info`/`warn`/`erro` | assinatura + pagamento | `shared.outbox.OutboxPublisher` |
| Falha temporária da outbox registrada como `warn` | assinatura + pagamento | `shared.outbox.OutboxPublisher` |
