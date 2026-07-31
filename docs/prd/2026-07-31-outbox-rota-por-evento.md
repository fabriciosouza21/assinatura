# PRD: Outbox roteia eventos pelo tipo, não pelo tópico fixo

**Date:** 2026-07-31
**Status:** Draft
**Entrega:** BE-7 do roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md` (plano técnico `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`, §5 BE-7)
**Contrato:** `docs/contratos/contrato-eventos-kafka.puml` (evento `AssinaturaSolicitada`) e tabela de contratos de renovação no plano técnico §2
**Design:** `docs/adesao/outbox-assinatura-solicitada.puml`, `docs/adesao/outbox-modelo-dados.puml`

## Problem

A outbox hoje funela todo evento para um único tópico Kafka. O `OutboxPublisher`
guarda um nome de tópico só (`assinatura-solicitada`) e envia para lá qualquer
evento pendente, ignorando a coluna `event_type` que a tabela `outbox` já guarda.
Com um único evento (`AssinaturaSolicitada`) isso é inofensivo. A renovação
adiciona novos eventos de saída no Assinatura Service: `RenovacaoSolicitada` e,
mais à frente, `AssinaturaRenovada`/`AssinaturaSuspensa`. Sem rotear por tipo, um
pedido de renovação cairia no tópico de adesão, onde nenhum consumer de renovação
escuta, e o fluxo de renovação nunca começaria.

Esta track faz a outbox escolher o tópico a partir do tipo do evento, de modo que
cada evento chegue ao seu consumer. É a fundação que permite à BE-9 publicar o
pedido de renovação no lugar certo.

## Background

A tabela `outbox` (migration `V5`) já carrega `event_type VARCHAR(64) NOT NULL`, e
`OutboxEvent` o expõe via `getEventType()`. O único produtor hoje
(`SolicitarAssinatura`) grava `event_type = "AssinaturaSolicitada"`. O dado percorre
o fluxo ponta a ponta; o publisher simplesmente não o usa.

O contrato de renovação (plano §2) trava `RenovacaoSolicitada` no tópico
`renovacao-solicitada`, com key `assinaturaId`, produzido pelo Assinatura Service
via outbox. O `.puml` formal (Gate 0) está pendente; a tabela do §2 é a fonte da
verdade até ele existir. A decisão D-R1 do plano fixa a mudança: o roteamento vira
função do `eventType` através de um mapa configurável `eventType -> tópico`, no
lugar do tópico fixo hardcoded em `OutboxPublisher.java:77` (lido em `:50`).

## Requirements

### Must Have

- O `OutboxPublisher` resolve o tópico de destino a partir do `eventType` do
  evento, via mapa configurável `eventType -> tópico`, em vez do único tópico fixo
  que detém hoje.
- O mapa cobre os eventos travados: `AssinaturaSolicitada -> assinatura-solicitada`
  (existente) e `RenovacaoSolicitada -> renovacao-solicitada` (renovação).
- Um evento cujo `eventType` não tem tópico mapeado falha rápido (tratado como
  falha de publicação pela política existente: incrementa tentativas e, ao esgotar,
  vai a `FALHA`/DLQ persistida). Não é descartado silenciosamente nem enviado ao
  tópico errado.
- O tópico `renovacao-solicitada` é declarado por um bean `NewTopic` no lado
  Assinatura (o produtor declara seu tópico), sem depender de auto-create.
- Nenhuma mudança no fluxo de adesão: `AssinaturaSolicitada` segue sendo publicado
  em `assinatura-solicitada` com key `assinaturaId`, entrega pelo menos uma vez,
  dedup por `eventId` no consumidor.

### Should Have

- O mapa de rotas é externalizado em `application.yaml`, de modo que adicionar um
  novo par `eventType -> tópico` seja uma mudança de configuração, não de código.
- O comportamento de tipo desconhecido (falha rápida) é coberto por um teste.

### Out of Scope

- Produzir `RenovacaoSolicitada` (isso é BE-9, que depende desta track).
- O domínio `Renovacao`, as colunas de ciclo e a migration `V7` (BE-8).
- O consumer de `renovacao-resultado` e os produtores
  `AssinaturaRenovada`/`AssinaturaSuspensa` (BE-10). As entradas de rota desses
  eventos internos entram quando esses produtores existirem.
- Qualquer mudança no schema da tabela `outbox` ou na query `buscarPublicaveis`.
  A coluna `event_type` já existe; a query já retorna todos os eventos publicáveis
  independente do tipo, e o roteamento acontece no envio.
- Gateway, Pagamento Service e mock (Stream Pagamento).

## Constraints

- A tabela `outbox` é controlada por Flyway (`ddl-auto: validate`); `event_type`
  já existe desde `V5`. Não há migration nesta track.
- O contrato de eventos Kafka está travado; nomes de tópico e keys não mudam sem
  bump de versão.
- Convenções do projeto: Javadoc em todo tipo/método público, Google Java Style,
  `make verify` verde (só unitário; integração via `make test-integration` ao fim).
- Os testes atuais do publisher hardcodam `assinatura-solicitada`; precisam
  continuar passando após a mudança de roteamento.

## Decisoes

- **Roteamento por eventType, não tópico fixo.** Resolvido (D-R1). O publisher lê
  um mapa `eventType -> tópico` e resolve o tópico por evento.
- **Mapa configurável.** Resolvido (plano DoD + confirmação). A tabela de rotas é
  um mapa externalizado em configuração, não uma constante em código. Observação:
  isto desvia da convenção atual de propriedades flat `app.kafka.topico-*`; o mapa
  passa a ser o padrão de roteamento, mantendo os nomes de tópico individuais
  sobrescrevíveis por variável de ambiente.
- **Forma da propriedade do mapa.** Resolvido (confirmado). Mapa dedicado ligado
  via `@ConfigurationProperties` (ex.: `app.kafka.rotas-evento-topico`), com os
  nomes de tópico individuais sobrescrevíveis por env. Desvia das propriedades flat
  atuais em prol de um roteamento declarativo no yaml, casando com o "mapa
  configurável" do plano.
- **Falha rápida em eventType desconhecido.** Resolvido (default). Um tipo não
  mapeado é tratado como falha de publicação (conta para retry/DLQ), não descartado
  nem enviado a um tópico padrão.

## Decisoes abertas (defaults assumidos, confirmar)

- **Entradas para os eventos internos `AssinaturaRenovada`/`AssinaturaSuspensa`.**
  Default: BE-7 entrega só as entradas travadas (`AssinaturaSolicitada`,
  `RenovacaoSolicitada`). As entradas dos eventos internos entram na BE-10, quando
  os produtores existirem e seus tópicos forem travados. Racional: inventar nomes
  de tópico agora seria antecipar o contrato.

## Acceptance Criteria

### Roteamento do evento existente preservado
- Given um evento `PENDENTE` com `event_type = AssinaturaSolicitada`, when o
  publisher executa, then o evento é publicado no tópico `assinatura-solicitada`
  com key `assinaturaId` e passa a `PUBLICADO`.

### Roteamento do novo evento
- Given um evento `PENDENTE` com `event_type = RenovacaoSolicitada`, when o
  publisher executa, then o evento é publicado no tópico `renovacao-solicitada`
  (não em `assinatura-solicitada`) com key `aggregateId` e passa a `PUBLICADO`.

### Tipo desconhecido falha rápido
- Given um evento `PENDENTE` cujo `event_type` não está no mapa de rotas, when o
  publisher tenta publicar, then a publicação é tratada como falha (incrementa
  tentativas / DLQ conforme a política existente) e o evento não é enviado a
  nenhum tópico.

### Tópico de renovação declarado
- Given a aplicação sobe, then o tópico `renovacao-solicitada` é declarado via
  `NewTopic`, sem depender de auto-create.

### Sem mudança de schema
- Given a migration atual é `V6`, when a track entrega, then nenhuma migration
  nova é criada e a coluna `event_type` existente é reutilizada.

### Adesão inalterada
- Given o fluxo de adesão existente, when `POST /assinaturas` publica, then
  `AssinaturaSolicitada` segue para `assinatura-solicitada` exatamente como antes
  (o teste existente de publicação permanece verde).
