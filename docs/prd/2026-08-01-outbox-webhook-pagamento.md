# PRD: Outbox no Pagamento Service para publicar resultados de cobrança

**Data:** 2026-08-01
**Status:** Draft
**Entrega:** continuação do BE-13 (webhook de renovação), roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md`
**Serviço:** Pagamento
**Versão alvo:** `0.3.0`
**Contrato de referência:** `docs/contratos/contrato-eventos-renovacao.puml` (renovação) e `docs/contratos/contrato-eventos-kafka.puml` (adesão)

## Problema

O webhook de pagamento publica o resultado de uma cobrança no Kafka aguardando o ACK do broker **dentro da transação** que persiste a decisão. É o achado M6 do review do BE-13.

Três dores. Com o broker lento ou fora do ar, cada notificação segura uma conexão do pool até o timeout; sob volume, o serviço esgota as conexões do banco e deixa de atender qualquer webhook. O ACK do Kafka vira dependência crítica do caminho do cliente: um atraso do broker atrasa a resposta do webhook, e o gateway reenvia a notificação. E se o Kafka confirma mas o commit da transação falha, o reenvio publica o mesmo resultado duas vezes, exigindo dedup em todos os consumidores.

Quem sente é operação e negócio: indisponibilidade do serviço, e assinaturas que pagaram mas não ativam nem renovam no tempo.

## Background

- O Assinatura Service resolveu o mesmo problema na adesão (BE-3): a outbox grava o evento na **mesma transação** da entidade, e um publisher (`@Scheduled`) drena a fila publicando no Kafka com retry, backoff + jitter e DLQ persistida após 3 tentativas. Padrão em produção no projeto, com testes e operação conhecidas.
- O webhook do Pagamento tem dois fluxos de publicação, ambos com `send().get()` bloqueante: adesão (`PagamentoStatusAtualizado` em `pagamento-status-atualizado`) e renovação (`PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas` em `renovacao-resultado`).
- Com a outbox, o webhook grava o evento `PENDENTE` e responde na hora; o ACK do broker sai do caminho do cliente e passa para o publisher.

## Requisitos

### Must Have

- A decisão de uma cobrança grava o evento de resultado na tabela `outbox` em status `PENDENTE`, na **mesma transação** que persiste a decisão (aprovação/esgotamento de renovação, atualização de cobrança de adesão). Se a transação voltar, o evento não existe.
- Um `OutboxPublisher` (`@Scheduled`) drena a outbox: seleciona `PENDENTE` prontos para envio, publica no tópico do eventType e marca `PUBLICADO` (`publicado_em`) **somente após o ACK do Kafka**.
- Em falha de publicação, incrementa `tentativas`, registra `ultimo_erro` e reagenda com backoff exponencial + jitter. Após 3 tentativas, marca `FALHA` (`falhou_em`) — DLQ persistida para reprocessamento manual, sem retry automático.
- O mapeamento eventType → tópico cobre os três eventos de saída: `PagamentoStatusAtualizado` → `pagamento-status-atualizado`, `PagamentoRenovacaoAprovado` e `RenovacaoTentativasEsgotadas` → `renovacao-resultado`. Tópicos já declarados (sem mudança).
- Parâmetros externalizados em `app.outbox.*`: intervalo de polling (default 5000ms), tamanho do lote (default 100), máximo de tentativas (3), backoff inicial (1s), jitter (500ms), com defaults em `.env.example`.
- Migration nova `V7` criando a tabela `outbox` (espelho do assinatura) com índice de polling parcial.

### Should Have

- Polling com `FOR UPDATE SKIP LOCKED`, permitindo múltiplas instâncias do publisher sem processar a mesma linha.
- Logs estruturados dos eventos de publicação (publicado, falha, esgotado) com `eventId`, `attempt` e `reasonCode`.
- Eventos de renovação continuam carregando `eventId` próprio para o consumidor deduplicar.

### Out of Scope

- Cleanup da fila `PUBLICADO`. O assinatura nunca expurga; o pagamento herda o padrão e registra o débito para os dois serviços.
- Reprocessamento automático de eventos `FALHA`. Fica o reprocessamento manual, igual ao assinatura.
- Mudança no contrato dos eventos, nos tópicos ou nos consumidores.
- Reconciliação de cobranças `PENDING` abandonadas (decisão O-R4 do 0.2.0).

## Constraints

- Schema Flyway-owned (`ddl-auto: validate`): a tabela `outbox` entra por migration `V7` (V6 consumida pelo BE-13), sem `ddl-auto` de criação.
- Entrega *at-least-once*: o publisher pode republicar um evento após ACK ok + commit falho; consumidores deduplicam por `eventId` (contrato já existente).
- O envio ao Kafka permanece bloqueante **dentro da transação do publisher**, marcando `PUBLICADO`/`FALHA` com o resultado do ACK conhecido antes do commit (padrão aceito no assinatura). O que muda é que ele sai da transação de `decidir` (caminho do cliente).
- Latência de ativação/renovação: o evento chega ao Assinatura Service no próximo ciclo do publisher (default 5s), não mais no ACK da resposta do webhook. Aceitável para o fluxo.
- Convenções do projeto: Google Java Style, Javadoc obrigatório, CQRS Lite, JUnit 5 + AssertJ, `make verify` e `make test-integration` verdes em `services/pagamento`.

## Decisões

- **Escopo: adesão + renovação.** Resolvido. Ambos os `publicar()` viram gravação na outbox; nenhum fluxo do webhook fica com `send().get()` bloqueante. Fix completo do M6.
- **Sem cleanup da fila.** Resolvido. Espelha o assinatura; débito anotado para resolver nos dois serviços depois.
- **Merge do BE-13 depois.** Resolvido. O outbox entra na mesma branch `feat/prd-be13-webhook-renovacao`; um review único cobre BE-13 + M6.
- **REQUIRES_NEW para publicar descartado.** O padrão assinatura usa a transação do publisher; `REQUIRES_NEW` quebraria a atomicidade estado+evento e não libera conexão (só troca qual fica presa).

## Acceptance Criteria

### Gravação atômica
- Given uma decisão de renovação (aprovada ou esgotada) ou de adesão (status atualizado), when o webhook processa, then o evento é gravado `PENDENTE` na outbox na mesma transação da decisão, e a resposta do webhook não espera o ACK do Kafka.

### Publicação assíncrona
- Given um evento `PENDENTE` na outbox, when o publisher executa, then o evento é publicado no tópico do seu eventType com key `assinaturaId` e passa a `PUBLICADO` com `publicado_em` preenchido.

### Retry em falha
- Given uma falha ao publicar com tentativas restantes (< 3), then o evento permanece `PENDENTE`, `tentativas` é incrementado e `proxima_tentativa_em` é reagendada com backoff + jitter.

### DLQ persistida
- Given três falhas consecutivas, then o evento passa a `FALHA` com `ultimo_erro` e `falhou_em`, sem republicação automática.

### Broker degradado não derruba o serviço
- Given o broker fora do ar e notificações chegando, when o webhook processa, then ele grava na outbox e responde sem bloquear, e nenhuma conexão do pool fica presa; o evento aguarda o broker no retry do publisher.

### Dupla publicação absorvida
- Given um ACK ok seguido de falha de commit no publisher, when o evento é reprocessado, then ele é republicado; o consumidor deduplica por `eventId` (at-least-once).
