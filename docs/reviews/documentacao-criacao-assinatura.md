# Revisão de documentação: feature de criação de assinatura

**Branch:** `feature/correlacao-trace-assinatura-id`
**Escopo:** PRDs, ADR, contrato de eventos e diagramas PlantUML relacionados à criação de assinatura (`POST /assinaturas`, `GET /assinaturas/{uuid}`, outbox de `AssinaturaSolicitada`, confirmação de pagamento), comparados campo a campo com a implementação em `services/assinatura`.
**Data:** 2026-08-01
**Método:** Leitura cruzada de cada documento contra o código-fonte correspondente (entidades, records, repositórios, migrations, configuração).

## Resumo

Os documentos "vivos" — o PRD de solicitação/consulta, o ADR 0001, o contrato de eventos Kafka e o diagrama novo `docs/assinatura/fluxo-assinatura.puml` — estão consistentes com o código. As divergências relevantes estão concentradas nos diagramas PlantUML mais antigos da pasta `docs/adesao/`, que parecem ser rascunhos de design anteriores às decisões finais e nunca foram atualizados ou removidos depois delas.

## Divergências

### 1. PRD documenta um mecanismo de concorrência que o código não usa

**Arquivo:** `docs/prd/2026-07-30-solicitacao-consulta-assinatura.md` (seção Should Have)

O PRD afirma que a regra "um usuário, uma assinatura aberta" é garantida por "índice único parcial... **somado a** `SELECT ... FOR UPDATE` na transação". Na implementação, `SolicitarAssinatura.executar()` (`SolicitarAssinatura.java:66-81`) não usa `FOR UPDATE` — depende inteiramente do índice único parcial + `catch (DataIntegrityViolationException)`. O método `buscarPorUuidParaAtualizacao` (que faz o `FOR UPDATE`) existe no repositório, mas só é chamado por `ProcessarPagamento`, nunca no caminho de criação.

Não é um bug funcional (o critério de aceite "exatamente uma requisição recebe 202, a outra 409" continua satisfeito só com o índice único), mas o texto do PRD descreve um mecanismo que não existe no código.

### 2. `docs/adesao/cadastro-usuario-assinatura.puml` está desatualizado

Esse diagrama (apenas movido de pasta no diff atual, conteúdo intocado) mostra:
- Corpo da requisição de `POST /assinaturas` com `id`, `dataInicio`, `dataExpiracao` e `status`, além de `usuarioId`/`plano` (linhas 31-40);
- Header `Idempotency-Key` na requisição (linha 28).

Ambos foram explicitamente descartados no PRD: "Resolvido. O `POST /assinaturas` recebe o input enxuto `{usuarioId, plano}`" e `Idempotency-Key` listado como *Out of Scope* (fica para o BE-5). `AssinaturaRequest.java` de fato só tem `usuarioId` e `plano`. O diagrama novo `docs/assinatura/fluxo-assinatura.puml` já reflete o contrato correto — o antigo deveria ser removido ou marcado como superado.

### 3. Javadoc aponta para um caminho de arquivo que não existe mais

**Arquivos:** `AssinaturaSolicitada.java` e `PagamentoStatusAtualizado.java` em ambos os serviços (`assinatura` e `pagamento`), mais um teste — 6 arquivos ao todo.

Todos referenciam `docs/contrato-eventos-kafka.puml`. O commit `0e691c8` ("docs: reorganiza diretorios de docs por dominio/release") moveu o arquivo para `docs/contratos/contrato-eventos-kafka.puml`, mas nenhuma das 6 referências no código foi atualizada.

### 4. Quatro diagramas de outbox descrevem um recurso que não existe: status `DESCARTADO` e serviço de recuperação manual

**Arquivos:** `docs/adesao/outbox-diagrama-estados.puml`, `outbox-diagrama-classes.puml`, `outbox-diagrama-componentes.puml`, `outbox-dlq-assinatura-solicitada.puml`

Todos mostram um 4º estado `DESCARTADO`, um `OutboxRecoveryService`/"Recuperação Outbox", um componente "Monitoramento" que gera alerta, e um fluxo completo de reprocessamento manual (`UPDATE outbox SET status = PENDENTE, tentativas = 0, ...`).

Nada disso existe no código: `OutboxStatus.java` só tem 3 valores (`PENDENTE`, `PUBLICADO`, `FALHA`); não há nenhuma classe de recovery/monitoramento em `services/assinatura/src/main/java`. O PRD da outbox já lista "Interface humana de reprocessamento de eventos `FALHA`" como *Out of Scope*, então não é uma regressão — mas os diagramas apresentam esse fluxo como se já existisse, sem nenhuma nota de "planejado/futuro".

### 5. `outbox-diagrama-classes.puml` — nomes de classe/método e um tipo incorretos

**Arquivo:** `docs/adesao/outbox-diagrama-classes.puml`

- `SolicitarAssinaturaService` → a classe real é `SolicitarAssinatura` (sem sufixo `Service`).
- `AssinaturaSolicitadaEvent` → a classe real é `AssinaturaSolicitada` (sem sufixo `Event`).
- `AssinaturaRepository.salvar()` / `OutboxRepository.salvar()` → são `JpaRepository`, o método herdado é `save()`, não `salvar()`.
- `EventPublisher` / `KafkaEventPublisher` (interface + implementação) → não existem; `OutboxPublisher` chama `KafkaTemplate` diretamente, sem essa camada de abstração.
- `Assinatura.usuarioId: UUID` → no código é `Long` (é o FK interno, não o uuid público — o diagrama inverte a própria convenção "id Long interno / uuid público" definida no PRD).
- `Assinatura.valor: BigDecimal` → nunca existiu na entidade real. Divergência já reconhecida pelo próprio PRD da outbox: "diverge do rascunho `outbox-modelo-dados.puml`, que a desenha — optou-se por não duplicar dado já existente no enum". O diagrama é um rascunho pré-decisão que não foi corrigido depois.

### 6. `outbox-modelo-dados.puml` — colunas que não existem na tabela real

**Arquivo:** `docs/adesao/outbox-modelo-dados.puml`

Além de `assinatura.valor NUMERIC(19,2)` (item 5), o diagrama também mostra `assinatura.criado_em TIMESTAMPTZ`, que não está em `V3__cria_tabela_assinatura.sql`. Os índices, por outro lado, batem exatamente com as migrations reais (`uq_assinatura_aberta_usuario` e `idx_outbox_polling`).

### 7. Nome de coluna divergente em dois diagramas de sequência

**Arquivos:** `docs/adesao/outbox-assinatura-solicitada.puml:62`, `outbox-dlq-assinatura-solicitada.puml:23,31`

Usam `published_at` / `failed_at` (inglês) nos `UPDATE outbox`. A tabela real usa `publicado_em` / `falhou_em` (português, `V5__cria_tabela_outbox.sql`), consistente com o resto do schema.

### 8. `outbox-assinatura-solicitada.puml` — dois erros pontuais no exemplo

- Linha 17: `Idempotency-Key: uuid` no header do `POST /assinaturas` — mesmo problema do item 2; fora de escopo até o BE-5.
- Linha 32: exemplo de evento com `"valor": 49.90` — não corresponde a nenhum plano real (`BASICO` 19.90, `PREMIUM` 39.90, `FAMILIA` 59.90). Aparenta ser erro de digitação no exemplo.

## O que está correto (validado, sem divergência)

- `docs/assinatura/fluxo-assinatura.puml` (novo) — passo a passo bate com `SolicitarAssinatura`, outbox e `ProcessarPagamento`.
- `docs/contratos/contrato-eventos-kafka.puml` — campos de `AssinaturaSolicitada` e `PagamentoStatusAtualizado` batem exatamente com os records Java, incluindo `paymentId` em `PagamentoStatusAtualizado`.
- `docs/openapi/assinatura.yaml` — bate com request/response/status codes, exceto a validação de formato de `usuarioId` (ver finding relacionado no review de código, item "usuarioId sem validação de formato").
- ADR 0001 (endpoints públicos) — implementado exatamente como decidido em `SecurityConfig`.
- Mecânica central da outbox: gravação atômica, `FOR UPDATE SKIP LOCKED`, backoff exponencial + jitter, 3 tentativas, `tamanho-lote` default 100 — todos os valores nos diagramas batem com os defaults reais em `application.yaml`.
- Índices únicos/parciais descritos em `outbox-modelo-dados.puml` batem exatamente com as migrations `V3` e `V5`.

## Recomendações

1. Atualizar ou remover `docs/adesao/cadastro-usuario-assinatura.puml` (superado por `docs/assinatura/fluxo-assinatura.puml`).
2. Corrigir as 6 referências de path para `docs/contratos/contrato-eventos-kafka.puml` no Javadoc.
3. Adicionar uma nota explícita de "não implementado / fora de escopo" nos 4 diagramas que descrevem `DESCARTADO`/recovery, ou movê-los para uma pasta de design futuro separada da documentação do estado atual.
4. Alinhar `outbox-diagrama-classes.puml` e `outbox-modelo-dados.puml` aos nomes de classe/campo/coluna reais (ou explicitar que são diagramas de design conceitual, não 1:1 com o código).
5. Corrigir `published_at`/`failed_at` para `publicado_em`/`falhou_em` nos diagramas de sequência da outbox.
6. Corrigir o valor de exemplo (`49.90` → um valor de plano real) em `outbox-assinatura-solicitada.puml`.
