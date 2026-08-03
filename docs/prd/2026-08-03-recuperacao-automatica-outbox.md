# PRD: Recuperação automática da outbox

**Data:** 2026-08-03
**Status:** Rascunho
**Escopo:** BE-24 do roadmap consolidado. Desdobra o Must Have "Recuperação
automática da outbox" do PRD guarda-chuva
`docs/prd/2026-08-02-dlq-recuperacao-observabilidade.md` em um documento focado,
executável em worktree própria.

## Problema

Quando a publicação de um evento no Kafka esgota as tentativas, o evento é
marcado `FALHA` na tabela `outbox` e fica preso no banco para sempre. Não há
scheduler, endpoint ou job que o retome. Em produção, um evento de negócio
importante (ativação, renovação, suspensão, cancelamento) pode se perder de
forma silenciosa até alguém abrir SQL no banco.

Quem sente a dor é o **operador**, que não tem como saber que há falhas
acumulando e não tem como repô-las sem intervenção manual direta no banco. O
risco é duplo: o evento fica retido indefinidamente, e a correção exige acesso
privilegiado que fere a idempotência e a auditoria.

O buraco existe nos dois serviços. A publicação (`OutboxPublisher`) e a política
de retry (`RetryPolicy`, 3 tentativas com backoff exponencial e jitter) estão
sólidas em ambos. O que não existe é o **tratamento do que chega ao estado
terminal `FALHA`**.

## Contexto

A auditoria do desafio confirmou que a mecânica de envio para `FALHA` está
correta nos dois serviços. Hoje:

- `OutboxStatus` tem três valores: `PENDENTE`, `PUBLICADO`, `FALHA`. Não há
  caminho de volta do terminal.
- `OutboxPublisher` publica apenas `PENDENTE`, com `FOR UPDATE SKIP LOCKED`.
  Após esgotar a `RetryPolicy`, o evento vira `FALHA` e sai do critério de
  seleção do publisher. Ninguém mais o lê.
- O `OutboxEvent` não tem contador de ciclos de recuperação. Não há como
  distinguir "falhou uma vez" de "falhou depois de recuperar".

Existe uma **base pré-existente** na branch local `worktree-feat-outbox-dlq-recovery`
(2 commits de 2026-08-01) que implementa a recuperação **somente no serviço de
assinatura**. Ela está funcional e testada, mas tem dois problemas que inviabilizam
um merge direto:

1. Usa o pacote antigo `com.globo.assinatura.outbox`, anterior ao ADR 0002. O
   `develop` agora usa `com.globo.assinatura.shared.outbox`. Cherry-pick seco
   quebra.
2. Vem embaralhada com a refatoração de pacotes (336 arquivos no diff). Não dá
   para pegar a branch inteira.

A contraparte no serviço de pagamento **não existe nem na branch**. Precisa ser
construída por espelhamento.

## Requisitos

### Must Have

- **Status recuperável.** Um novo status `RETENTATIVA_DLQ` marca eventos que
  voltam do terminal `FALHA` ao ciclo de publicação. O publisher passa a
  publicar `PENDENTE` e `RETENTATIVA_DLQ`.
- **Scheduler de recuperação.** Um scheduler promove periodicamente eventos
  `FALHA` que ultrapassaram o tempo de quarentena configurável de volta a
  `RETENTATIVA_DLQ`, devolvendo-os ao publisher.
- **Limite de ciclos.** Cada evento carrega um contador de ciclos de recuperação.
  Ao atingir o máximo configurável, o evento permanece `FALHA` terminal e sai do
  critério do scheduler, ficando acessível ao mecanismo de recuperação manual
  (BE-26, fora deste escopo).
- **Concorrência segura.** O scheduler usa `FOR UPDATE SKIP LOCKED`, como o
  publisher já faz, para suportar múltiplas instâncias sem duplicar a promoção.
- **Presença nos dois serviços.** O mecanismo deve existir no Assinatura e no
  Pagamento, pelo mesmo fluxo. Não pode haver divergência de comportamento entre
  os dois.
- **Reset do ciclo.** Ao recuperar, o contador de tentativas do evento é zerado
  para que a `RetryPolicy` rode completa novamente, e o contador de ciclos é
  incrementado.

### Out of Scope

- **Recuperação manual assistida** (BE-26). Após esgotar os ciclos automáticos,
  um operador retoma eventos por um meio que não seja SQL cru. É entrega
  separada, consome a base deste PRD.
- **Observabilidade de falhas** (BE-25). Métricas de eventos em `FALHA` e volume
  na DLQ de consumer. Entrega separada, consome a base deste PRD.
- **Replay da DLQ de consumer** (BE-27). Mensagens dos tópicos `-dlq` dos
  `@KafkaListener` ficam paradas. É outro fluxo de DLQ, disjunto da outbox.
- **Trocar a política de retry/backoff existente.** `RetryPolicy` (backoff
  exponencial, jitter, 3 tentativas) fica como está.
- **Mudar o padrão de outbox** (event sourcing, CDC/Debezium, broker
  transacional). A outbox write-aside com publisher por polling atende.

## Restrições

- **Sem quebra de contrato Kafka.** Tópicos, chaves e payloads existentes não
  mudam de forma incompatível. O novo status é interno à tabela `outbox`, não
  viaja no Kafka.
- **Configuração externalizada.** Timeout de quarentena, máximo de ciclos e
  intervalo do scheduler seguem o padrão `APP_*` já adotado pelos dois serviços.
- **Idempotência preservada.** Recuperar um evento não pode duplicar efeitos de
  negócio. Os guards de idempotência por `event_id` que já existem nos consumers
  continuam válidos sob recuperação.
- **Padrão de logs estruturados.** SLF4J Fluent, `event` em `snake_case`, sem
  payload nem PII, em toda nova decisão. Toda cadeia termina em `.log()`.
- **Concorrência segura.** `FOR UPDATE SKIP LOCKED` no scheduler de recuperação,
  como o publisher já faz.
- **Numeração de migrations coordenada.** Cada serviço recebe uma migration nova
  para adicionar a coluna `ciclos_recuperacao`. Reservar V13 (Assinatura) e V11
  (Pagamento), coordenando entre worktrees do mesmo serviço para não quebrar a
  baseline do Flyway no merge.
- **Convenções do monorepo.** Commits PT-BR, Conventional Commits, Google Java
  Style, Javadoc obrigatório em todo símbolo público novo.

## Critérios de Aceitação

### Status recuperável e publisher

- Dado um evento recuperado para `RETENTATIVA_DLQ`, quando o publisher roda,
  então ele o seleciona junto com `PENDENTE` e o publica no Kafka normalmente.
- Dado o publisher rodando em múltiplas instâncias, quando duas tentam publicar o
  mesmo evento, então só uma o faz, sem duplicação, pelo `FOR UPDATE SKIP LOCKED`
  já existente ampliado para cobrir `RETENTATIVA_DLQ`.

### Scheduler de recuperação

- Dado um evento em `FALHA` há mais tempo que o timeout de quarentena configurado
  e abaixo do limite de ciclos, quando o scheduler de recuperação roda, então o
  evento é promovido a `RETENTATIVA_DLQ` com o contador de tentativas zerado e o
  contador de ciclos incrementado.
- Dado um evento em `FALHA` há menos tempo que o timeout de quarentena, quando o
  scheduler avalia, então ele não é promovido.
- Dado o scheduler rodando em múltiplas instâncias, quando duas tentam recuperar
  o mesmo evento, então só uma o faz, sem duplicação, pelo `FOR UPDATE SKIP
  LOCKED`.

### Limite de ciclos

- Dado um evento que atingiu o número máximo de ciclos de recuperação, quando o
  scheduler avalia, então ele permanece `FALHA` terminal e não é mais promovido,
  ficando acessível ao mecanismo de recuperação manual.
- Dado um evento recuperado, quando volta a falhar na publicação e esgota a
  `RetryPolicy`, então retorna a `FALHA` e é elegível a um novo ciclo de
  recuperação enquanto não atingir o limite de ciclos.

### Presença nos dois serviços

- Dado o mecanismo presente nos dois serviços, quando o Pagamento tem um evento
  em `FALHA`, então ele é recuperado pelo mesmo fluxo do Assinatura.
- Dado um evento de cancelamento ou de resultado de renovação publicado pelo
  Pagamento, quando ele falha e é recuperado, então o consumer do Assinatura o
  processa preservando a idempotência por `event_id`.

### Isolamento da base pré-existente

- Dado o cherry-pick dos commits `2f6eaa5` e `c9b6167` da branch
  `worktree-feat-outbox-dlq-recovery`, quando aplicados sobre o `develop` atual,
  então o pacote é corrigido para `com.globo.assinatura.shared.outbox` e a
  migration renumerada para V13, sem arrastar a refatoração de pacotes que
  acompanha a branch original.

## Notas operacionais

- **Retenção de `FALHA` terminal.** Eventos que esgotam `maxCiclos` permanecem
  em `FALHA` definitivamente, sem path automático de arquivamento; o índice
  parcial `idx_outbox_recuperacao_dlq` cresce enquanto houver acúmulo. A limpeza
  operacional (ex.: job de archive) está fora deste escopo e deve respeitar a
  retenção exigida por auditoria.
- **Janela de elegibilidade espalhada.** A recuperação agenda a próxima
  tentativa com backoff inicial + jitter da `RetryPolicy`, evitando thundering
  herd no publisher quando um lote grande de eventos falha de forma correlacionada.
