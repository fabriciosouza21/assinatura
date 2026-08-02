# Code Review: webhook de renovação decide e publica resultado (BE-13)

**Branch:** `feat/prd-be13-webhook-renovacao`
**Escopo:** `ProcessarWebhookRenovacao`, `ProcessarWebhookPagamento` (despacho adesão vs. renovação), `TentativaCobranca` (máquina de status), `PagamentoRenovacao` (fábrica de tentativas), `TentativaCobrancaRepository` (predicado temporal + `findByPaymentId`), `CobrancaRenovacaoScheduler` (initial delay), eventos `PagamentoRenovacaoAprovado`/`RenovacaoTentativasEsgotadas`, config de backoff
**Data:** 2026-08-01
**Método:** Revisão autônoma com 3 revisores especializados (security, performance, quality) + auditoria red-team com verificação dos achados contra o código (18 arquivos modificados + 6 novos, incl. mock do gateway e migrations)

## Resumo

O desenho é sólido: a sequência de tentativas nasce no agregado (`PagamentoRenovacao.registrarTentativa`), a máquina de status é protegida por guarda de pendência, o publish-then-ACK é preservado no fluxo novo (503 do `DecisaoRenovacaoIndisponivelException` antes de gravar o eventId está correto, confirmado na auditoria), e o predicado temporal do scheduler tem cobertura de integração. O predicado novo é sargável pelo índice parcial existente (V5) — sem risco de seq scan no query do scheduler.

Nenhum achado High/Critical. Nove Medium, todos com fix pequeno e local. O maior valor da revisão está em dois pontos estruturais: (1) a dedup por eventId não funciona contra reenvios do mock (eventId novo por dispatch) e a decisão não tem lock otimista — duas janelas de dupla publicação; (2) o contrato do mock (sem reenvio em 503) torna irreversível a perda de decisão na corrida scheduler-vs-webhook.

## Veredito final

**Clean with suggestions** → **resolvido** (6 Medium resolvidos; 1 Medium adiado com justificativa; Lows resolvidos em sua maioria, 3 adiados). `make verify` e `make test-integration` verdes: 0 violações Checkstyle, Spotless limpo, 95 testes unitários + 16 de integração passando, mock do gateway `go vet`/`go build` limpos.

## Findings

### Corrigidos

#### [security] Decisão sem validação cruzada renovação/tentativa em `decidir` — **resolvido**

**Arquivo:** `services/pagamento/src/main/java/com/globo/pagamento/webhook/ProcessarWebhookRenovacao.java`

A tentativa era resolvida por `paymentId` sem exigir que pertencesse à renovação do pagamento despachado. `aprovar`/`esgotar` marcariam a tentativa errada e publicariam o evento com os IDs do outro agregado, silenciosamente.

**Teste RED:** `ProcessarWebhookRenovacaoTest.deveRejeitarDecisaoQuandoTentativaDeOutraRenovacao` — tentativa de `ren-A` com `paymentId=pay-X`, `decidir(pagamentoDeRenB, eventId, "pay-X", APPROVED)`; falhava aprovando e publicando.

**Correção:** após `encontrada.get()`, `decidir` exige `pagamento.getRenovacaoId().equals(tentativa.getRenovacaoId())`; a divergência lança `DecisaoRenovacaoIndisponivelException` (reenvio com a correlação certa resolve) com log fluente `event=resultado_renovacao_sem_tentativa` + `reasonCode=renovacao_divergente`.

#### [security] Corrida na decisão: `estaPendente()` sem lock — **resolvido**

**Arquivos:** `ProcessarWebhookRenovacao.java`, `TentativaCobrancaRepository.java` (novo `buscarPorPaymentIdParaAtualizacao`)

Dois webhooks concorrentes com eventIds distintos passavam `estaPendente()` antes do commit e ambos decidiam: APPROVED → dupla publicação; REJECTED-com-retry → segunda inserção de `numero+1` violava `uq_tentativa_cobranca_renovacao_numero` → 500 não tratado.

**Teste RED:** `ProcessarWebhookRenovacaoTest.deveBuscarTentativaComLockPessimista` — o comando ainda consultava via leitura simples; o stub do método com lock era `UnnecessaryStubbing`.

**Correção:** `buscarPorPaymentIdParaAtualizacao` com `@Lock(PESSIMISTIC_WRITE)` (JPQL) no `findByPaymentId` do fluxo de decisão. A segunda transação bloqueia até a primeira commitar e enxerga a tentativa já decidida → ignora. Reusa o desenho já adotado no consumer da adesão.

#### [security] Dedup ancorado em header não autenticado — **resolvido**

**Arquivos:** `WebhookPagamentoController.java`, `docker/mock-pagamento/main.go`

O HMAC cobria apenas o corpo; a chave de dedup era o header `X-Mock-Event-Id`, nunca cruzado com o `id` do corpo assinado. Replay de um corpo capturado com header fresco reprocessava. Agravante: o mock gerava eventId novo a cada dispatch, então a dedup nunca absorvia reenvio.

**Teste RED:** `WebhookPagamentoControllerTest.deveRetornar401QuandoEventIdDoHeaderDivergeDoCorpo` — corpo com `id` distinto do header; falhava com 200.

**Correção (serviço):** o controller rejeita em 401 (`WebhookInvalidoException`) quando o event id do header diverge do `id` do corpo assinado — a dedup passa a ancorar num valor coberto pelo HMAC.

**Correção (mock):** `dispatchWebhook` reenvia em 503 com o **mesmo** event id (até 3 tentativas, backoff 1s/2s/4s), como um gateway real: a dedup do destino funciona ponta-a-ponta e o 503 da corrida scheduler-vs-webhook não perde mais a decisão (fecha também o Low "[security] 503 irreversível no mock").

#### [performance] Sem índice em `tentativa_cobranca.payment_id` — **resolvido**

**Arquivo:** `db/migration/V6__cria_indice_tentativa_cobranca_payment_id.sql`

`findByPaymentId` rodava em todo webhook de renovação e viraria seq scan. A omissão era evidente pelo paralelo em V3 (`ix_cobranca_payment_id`).

**Correção:** `CREATE INDEX ix_tentativa_cobranca_payment_id ON tentativa_cobranca (payment_id) WHERE payment_id IS NOT NULL` (parcial, espelhando V3). Validado pela execução do Flyway nos testes de integração.

#### [performance] Lote ilimitado em transação única no scheduler — **resolvido**

**Arquivo:** `TentativaCobrancaRepository.buscarProntasParaCobrar`, `CobrancaRenovacaoScheduler`

O lote era ilimitado: loop serial com chamada HTTP bloqueante por tentativa, lock retido até o commit, conexão e persistence context acumulando. Encolhido com `LIMIT 200` + `ORDER BY id` (lote estável, o que sobra fica para o próximo ciclo), documentado no javadoc da query.

**Teste RED:** `TentativaCobrancaRepositoryTest.deveLimitarLoteSelecionadoEmUmUnicoCiclo` — 205 tentativas elegíveis; falhava devolvendo 205.

**Nota:** a janela de invisibilidade do `payment_id` (webhook chega antes do commit) foi mitigada dos dois lados: o mock agora reenvia com o mesmo eventId, e o lote caiu de "tudo" para 200 roundtrips. O `REQUIRES_NEW` por tentativa continua possível se o lote voltar a crescer, mas exige colaborador separado (o `@Transactional` de `cobrar()` é bypassado na auto-chamada de `agendar()`).

#### [quality] Log fora da convenção no handler novo — **resolvido**

**Arquivo:** `WebhookExceptionHandler.java`

`handleDecisaoIndisponivel` usava `log.warn` antigo, sem `event` snake_case e sem contexto, duplicando com menos informação o WARN fluente do command.

**Correção:** log removido do handler (o command já loga com `renovacaoId`, `paymentId`, `reasonCode` no ponto da falha); o javadoc do handler documenta que ali só o código HTTP é mapeado.

#### [quality] Sem teste da propagação de `DecisaoRenovacaoIndisponivelException` sem gravar dedup — **resolvido**

**Arquivo:** `services/pagamento/src/test/java/com/globo/pagamento/webhook/ProcessarWebhookPagamentoTest.java`

**Correção:** `devePropagarDecisaoIndisponivelSemGravarDedup` — `decidir` lança, `processar()` propaga e `eventoRepository.save` nunca é chamado (rede que impede a regressão da garantia central da exceção).

#### [quality] Sem integração ponta-a-ponta webhook → decisão → publicação — **resolvido**

**Arquivo:** `services/pagamento/src/test/java/com/globo/pagamento/webhook/WebhookRenovacaoIntegracaoTest.java` (novo)

**Correção:** teste `@SpringBootTest` + `@EmbeddedKafka` + Postgres real (porta 5433) exercitando `POST /webhooks/payments` com HMAC real → despacho → decisão (lock inclusa) → tópico `renovacao-resultado` → ACK → dedup. Dois cenários: aprovação (tentativa `APROVADA`, dedup gravada, payload no tópico) e 503 sem gravar dedup quando a tentativa ainda não existe.

#### [quality] Guards de construtor e `agendarPara(null)` sem teste — **resolvido**

`ProcessarWebhookRenovacaoTest` ganhou `deveRejeitarConstrucaoComBackoffNulo` e `deveRejeitarConstrucaoComBackoffNegativo`; `TentativaCobrancaTest` ganhou `deveRecusarAgendamentoSemInstante`.

#### [quality] Caminho `InterruptedException` do publish nunca testado — **resolvido**

`ProcessarWebhookRenovacaoTest.deveRestaurarFlagDeInterrupcaoQuandoPublishInterrompido` e análogo no `ProcessarWebhookPagamentoTest`, ambos com um `CompletableFuture` cujo `get()` lança `InterruptedException`, verificando a restauração do interrupt flag (com cleanup em `finally`).

#### [quality] Javadocs desatualizados — **resolvido**

`WebhookData.java` (campo agora é `externalReference`, não "uuid da assinatura correlacionada") e `ProcessarWebhookRenovacao` (construtor documenta o caso `null` de `backoffDias`).

#### [quality] Docs divergem do backoff implementado — **resolvido**

PRD (`docs/prd/2026-08-01-webhook-renovacao-decide-publica-resultado.md`) e `docs/renovacao/renovacao-mecanismo-retries.md` citavam janela "D+1, D+3, D+7"; o default implementado é `1,3` → três tentativas em D+0, D+1 e D+3, com o tamanho da lista definindo a contagem. Docs alinhados ao default e à semântica config-driven (D+1 após a 1ª recusa, D+3 após a 2ª).

### Adiados

- [performance] **`send().get()` dentro de transação** (`ProcessarWebhookRenovacao:186-196`) — o fix real é outbox, fora do escopo do BE-13 (feature de stream separada). Mantém a assimetria com a adesão; mitigação de pool/timeouts não foi aplicada para não ajustar números sem medição.
- [security] **Fallback silencioso para adesão** — quando `findByRenovacaoId` não resolve, o webhook segue o fluxo de adesão e queima o eventId. Mudar o comportamento exigiria distinguir "não é renovação" de "renovação sumida", o que quebra o fluxo de adesão atual; registrar em PRD futuro de reconciliação (O-R4).
- [quality] **`publicar` duplicado** entre `ProcessarWebhookPagamento` e `ProcessarWebhookRenovacao` — 2ª ocorrência; limiar de extração ainda não alcançado (tolerar até a 3ª).
- [quality] **Asserções `.contains(...)` sobre payload JSON bruto** — cosmético; os asserts novos do `WebhookRenovacaoIntegracaoTest` já verificam o contrato por campo, os antigos ficam como estão.
- [audit] **Dupla publicação com reenvio** (Kafka ACK ok + falha de commit = 500 → reenvio com o **mesmo** eventId agora, graças ao mock) — o consumer futuro de `renovacao-resultado` deve deduplicar por `renovacaoId`/`paymentId`, além de `eventId`.

## Padrões positivos (mantidos)

- Fábrica de tentativas no agregado: `PagamentoRenovacao.registrarTentativa()` gera a sequência de `numero`, construtor de `TentativaCobranca` package-private com validação (`renovacaoId` não vazio, `numero >= 1`). Bem testado (`PagamentoRenovacaoTest`, `TentativaCobrancaTest`, `CriarPagamentoRenovacaoServiceTest`).
- Guarda `estaPendente()`/`exigirPendente()` absorve reprocesso de webhook já decidido, com log em `DEBUG` (`resultado_renovacao_ja_decidido`).
- `DecisaoRenovacaoIndisponivelException` com javadoc que explica a corrida e por que absorver seria bug — decisão documentada no lugar certo; 503 antes do `save(eventId)` está correto (auditoria confirmou: gravar o dedup nesse ponto encalharia a tentativa para sempre).
- publish-then-ACK preservado: Kafka dentro da transação de `decidir`, `eventId` gravado só após confirmação; rollback desfaz status/agendamento.
- Predicado temporal do scheduler com text block e javadoc explicando o porquê; sargável pelo índice parcial V5 (BitmapOr de dois ranges contíguos); três testes de integração novos (futuro/vencido/decidida + SKIP LOCKED concorrente).
- Logging fluente com `event` + `reasonCode` nos commands novos e na migração do `ProcessarWebhookPagamento`.
- `@Scheduled(initialDelayString=...)` com javadoc honesto (escalonamento em produção + disputa em teste) e teste de integração ajustado junto.
- Renomeação consistente `assinaturaId()` → `externalReference()` em `WebhookEvent`/controller/command, sem sobras.
- Dedup gravada sob o `assinaturaId` da renovação (não sob o `renovacaoId`), intencional e testado.

## Como reproduzir

```bash
cd .worktrees/prd-be13-webhook-renovacao/services/pagamento
make verify            # lint + testes unitarios
make test-integration  # sobe Postgres na 5433; repositorio + scheduler + webhook end-to-end
cd ../../docker/mock-pagamento && go vet ./... && go build .
```
