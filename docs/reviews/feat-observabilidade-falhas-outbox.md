# Code Review: `feat/observabilidade-falhas-outbox`

**Data:** 2026-08-03
**Branch:** `feat/observabilidade-falhas-outbox` (worktree `.worktrees/feat-observabilidade-falhas-outbox`)
**Entregável:** BE-25. Métrica `outbox.eventos` (gauge por status) exposta em `/actuator/prometheus`
nos dois serviços, tornando falhas terminais da outbox visíveis sem log ou SQL.
**Diff:** 23 arquivos, 928 inserções, 319 remoções, 15 commits.
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

`micrometer-registry-prometheus` nos dois poms; exposição `health,prometheus`; `/actuator/prometheus`
no `permitAll` dos dois `SecurityConfig`; `OutboxMetricasScheduler` (gauge `outbox.eventos` com tags
`servico` e `status` em snake_case, atualização periódica via `@Scheduled` de
`app.outbox.metricas.intervalo-ms`); `OutboxRepository.contarPorStatus()` (native query de contagem
por status); pool de schedulers 3→4; coleção Bruno e PRD; remoção da skill defasada
`.claude/skills/run-assinatura/`. Critério do PRD: mesmo nome de métrica nos dois serviços com tag
`servico` (escolha do usuário sobre o exemplo `assinatura.outbox.eventos` do PRD), atualização
periódica, gauges zerados no arranque.

## Veredito

**Clean with suggestions.** Os 3 achados Medium e os Lows acionáveis foram endereçados com TDD
(RED→GREEN) e comitados; restam apenas dois itens documentados como acompanhamento (pré-existentes
ou mitigados). `make verify` GREEN nos dois serviços (assinatura 248 unit, pagamento 177 unit;
checkstyle e spotless limpos) e `make test-integration` GREEN (assinatura 42/42, pagamento 34/34).

## Achados endereçados (7 commits, RED primeiro)

### Medium

- **[performance] Contagem por status fazia full-table scan na tabela append-only**
  `OutboxRepository.java:73` (ambos). `SELECT status, COUNT(*) FROM outbox GROUP BY status` não usava
  os índices parciais (`idx_outbox_polling`, `idx_outbox_recuperacao_dlq`) e contava `PUBLICADO`
  (nunca purgado) só para descartar. Custo O(linhas) a cada 60s nos dois serviços contra o mesmo
  Postgres.
  - **RED:** `deveContarEventosPorStatus` passou a esperar apenas os status monitorados — falhou com
    `PUBLICADO=1L` inesperado.
  - **Fix:** `WHERE status IN ('PENDENTE','RETENTATIVA_DLQ','FALHA') GROUP BY status` → coberto pelos
    índices parciais (index-only para os status de polling).
  - Commits `0433d02`, `964c130`.

- **[quality] Falha na consulta congelava os gauges sem log estruturado**
  `OutboxMetricasScheduler.java:69` (ambos). Sem try/catch, qualquer falha de DB caía no handler
  padrão do scheduler (ERROR sem `event`, violando a convenção de logging) e os gauges mantinham o
  último valor — o sinal que a feature existe para mostrar morria justamente no cenário de falha.
  - **RED:** novo teste `deveManterGaugesInalteradosQuandoConsultaFalha` —
    `DataAccessResourceFailureException` propagava direto.
  - **Fix:** try/catch + `atWarn` com `event=outbox_metricas_atualizacao_falhou`, gauges preservados,
    agendamento continua (WARN para degradação recuperável, sem `setCause`).
  - Commits `619bc7f`, `afc7da5`.

- **[quality] Critério de aceitação do PRD sem cobertura de teste**
  Nenhum teste tocava `/actuator/prometheus`, a exposição ou o `permitAll`; verificação era só
  manual. Regressão (exposição errada, matcher de segurança) passaria em silêncio até o primeiro
  scrape.
  - **Fix:** `MetricasPrometheusEndpointTest` (novo, ambos) — `@SpringBootTest(RANDOM_PORT)` +
  `HttpClient` do JDK: `/actuator/prometheus` sem token → 200 com `outbox_eventos{servico=...,
  status="falha"}` e `/actuator/health` inalterado. Segue o padrão de
  `ConsultaAssinaturaAtivaSemTokenTest`; no pagamento, listeners do Kafka desligados como no
  `CobrancaRenovacaoSchedulerIntegracaoTest`.
  - Commits `b4d454d`, `1bf8baa`.

### Low

- **[quality] `OutboxStatus.valueOf` abortava a atualização inteira em status desconhecido**
  (frieze persistente a cada tick até deploy/restart). Refatorado para `Map<String, Long>` por nome
  + `getOrDefault(status.name(), 0L)`: status desconhecido é ignorado naturalmente, sem fluxo por
  exceção. RED com `STATUS_FUTURO` na consulta; dentro dos commits `619bc7f`/`afc7da5`.
- **[quality] Javadocs:** "nao terminal" listando `FALHA` (redigido neutro: "status monitorado do
  ciclo de publicacao e recuperacao") e self-reference em `services/pagamento` ("de
  services/pagamento" → "de services/assinatura"). Idem commits `619bc7f`/`afc7da5`.
- **[quality] UUIDs fixos no teste de integração do assinatura** (reuso de agregados entre métodos;
  fragilidade latente em base populada) → `UUID.randomUUID()`, espelhando o twin do pagamento.
  Dentro de `0433d02`.
- **[quality] Comentário do `.env.example` sobre o pool** não mencionava as métricas da outbox
  (drift com o comentário do yaml). Atualizado. Commit `0a90164`.
- **[quality] Gotchas da skill removida perdidos** — `driver.sh` e os gotchas operacionais
  (`make test-integration` derruba a stack inteira via `down -v` no mesmo projeto compose; ruído de
  `Unable to rollback` no boot é esperado e inofensivo) não estavam documentados em outro lugar.
  Migrados para `bruno/README.md` (seção "Gotchas"). A remoção da skill segue no PR (docs defasadas
  afirmavam `POST /assinaturas` sem JWT; na prática 401/403 nesta branch). Commit `0a90164`.

## Achados descartados (audit)

- **Falso positivo do scout:** `spring.application.name` "ausente" — a propriedade existe nos dois
  `application.yaml` (pré-existente, fora do diff); a tag `servico` resolve corretamente.
- **[performance] Colisão 4-way de schedulers a cada minuto** — mecanismo errado: `fixedDelay`
  agenda a próxima execução após a conclusão; com o publisher bloqueado por outage de Kafka restam
  3 threads livres para os 3 jobs de 60s. Recalibrado para o residual real (pool sem headroom, Low).
- **[performance] `initialDelay` para a primeira execução** — a 1ª rodada em t=0 é benéfica: atende
  o critério do PRD ("publica o valor corrente sem intervenção manual" desde o arranque) e o
  Flyway já migrou o schema antes do bean subir.

## Good patterns

- Gauges zerados no arranque e atualização all-or-nothing (nunca meio-atualizada), ambos testados.
- Padrão delta/baseline na integração — a única abordagem correta sob a base 5433 compartilhada.
- Log DEBUG estruturado com `event: outbox_metricas_atualizadas`, sem PII/payloads.
- Decisões documentadas (Object[]/projeção de interface, nome único + tag `servico`, pool 4).
- Endpoint de métricas coberto por teste de integração ponta a ponta real (HTTP, sem MockMvc).
- Paridade byte a byte entre os dois serviços (verificado via diff pós-`sed` de pacote).

## Audit notes

- **Recalibrado de High para Medium:** o full-table scan (perf) vs Low (security) — sem evidência de
  escala hoje (desafio de dev), mas custo cresce sem teto sobre tabela que nunca purga; o fix é uma
  linha e o teste novo travava o comportamento (corrigido junto).
- **Verificado clean:** sem SQL injection (query estática sem parâmetros), sem PII no scrape
  (payload JSONB nunca lido), whitelist de exposição correta (`env`/`heapdump` etc. fechados),
  tags sem injeção (enum + `spring.application.name` estático), gauges registrados uma única vez
  (sem leak por scrape), `permitAll` do `/actuator/prometheus` segue o precedente do `/actuator/health`.
- **Mitigado pelo M3:** o burst sincronizado de consultas dos dois serviços em t=60 no mesmo
  Postgres perdeu quase todo o peso com a query index-only — stagger de `initialDelay` não foi
  aplicado (quebraria a paridade entre serviços com config assimétrica; valor marginal).
- **Acompanhamento (pré-existente, fora do PR):** assimetria de produtor Kafka — assinatura sem
  `delivery.timeout.ms` bloqueia ~60s por lote em outage vs ~5s no pagamento; explica a latência do
  gauge `falha` no cenário monitorado. Já mapeado para o BE-28 no roadmap.
- **[security, Low] `/actuator/prometheus` sem auth** expõe métricas JVM/Kafka/volume — prática
  padrão em rede interna (Prometheus não faz JWT); mitigação em nível de deploy
  (`management.server.port/address` ou `hasIpAddress`) fica documentada como decisão de plataforma,
  não código.

## Plano de ação

1. Criar o PR (draft) para `develop` com `gh pr create` — título/corpo propostos no chat.
2. Merge em `develop`, remover a worktree (`git worktree remove
   .worktrees/feat-observabilidade-falhas-outbox`) e derrubar a stack docker se não estiver em uso.
3. Acompanhamento (fora deste PR): `delivery.timeout.ms` no produtor do assinatura (BE-28).
