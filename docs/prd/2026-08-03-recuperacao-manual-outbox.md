# PRD: Recuperação manual assistida da outbox

**Data:** 2026-08-03
**Status:** Rascunho
**Escopo:** BE-26 do roadmap consolidado. Desdobra o Must Have "Recuperação
manual assistida da outbox" em um documento focado, executável em worktree
própria.

## Problema

O BE-24 entregou a recuperação **automática** da outbox: um scheduler promove
eventos `FALHA` de volta a `RETENTATIVA_DLQ` após uma quarentena, até um teto de
ciclos. Mas o teto é finito. Quando um evento esgota todos os ciclos automáticos,
ele fica em `FALHA` **terminal**: nenhum scheduler o toca mais, e a única forma
de repô-lo é abrir SQL direto no banco.

Isso é perigoso em produção por três razões: exige acesso privilegiado ao banco,
não deixa trilha de auditoria, e pode quebrar a idempotência se feito errado
(resetar campos que não deveriam). O operador não tem um meio controlado de
intervir quando a recuperação automática não dá conta.

Quem sente a dor é o **operador**, que precisa de um caminho assistido para
identificar e retomar eventos terminais de forma segura e auditável.

## Contexto

A base para a recuperação manual já existe:

- **A query de seleção** (`OutboxRepository.buscarRecuperaveis`) faz o
  `SELECT ... WHERE status='FALHA'` com `FOR UPDATE SKIP LOCKED`, criada pelo
  BE-24.
- **O método de reset** (`OutboxEvent.recuperarParaRetentativa`) já transita o
  status para `RETENTATIVA_DLQ`, zera as tentativas e incrementa o contador de
  ciclos, devolvendo o evento ao publisher. A recuperação manual reusa essa
  mesma lógica, sem caminho novo de reset.
- **A autenticação admin já existe** na seed inicial (`V1__init.sql` insere um
  usuário `admin` com `ROLE_ADMIN`), e `UsuarioAutenticado.isAdmin()` é um helper
  pronto. O `JwtAuthenticationFilter` já converte a claim `role` em
  `SimpleGrantedAuthority`. Não há auth para construir do zero.

O BE-25 (implementando) expõe um gauge de eventos em `FALHA`, tornando o acúmulo
visível. O BE-26 é o **desfecho operacional** dessa visibilidade: quando o
operador vê eventos terminais acumulando, ele tem um meio assistido de agir,
preservando a idempotência e a auditoria.

A recuperação manual é **complementar** à automática, não substituta. O scheduler
do BE-24 cuida do caso comum (eventos que voltam com backoff e quarentena). O
BE-26 cuida do caso terminal: evento que esgotou os ciclos e precisa de decisão
humana.

## Requisitos

### Must Have

- **Listagem de eventos terminais.** Um endpoint lista os eventos em `FALHA`,
  com filtros úteis ao diagnóstico (tipo de evento, idade), paginado. É o que
  permite ao operador ver o que está preso e decidir o que retomar.
- **Retomada por `eventId`.** Um endpoint repõe um evento específico,
  identificado pelo seu `eventId`, devolvendo-o ao ciclo de publicação pela
  mesma lógica do scheduler automático (`recuperarParaRetentativa`). O reset é
  determinístico e feito pelo método de domínio já existente, não por SQL.
- **Idempotência preservada.** Retomar um evento não duplica efeito de negócio.
  Os guards de idempotência por `eventId` que já existem nos consumers continuam
  válidos sob a republicação.
- **Autorização por `ROLE_ADMIN`.** Os endpoints exigem JWT com `ROLE_ADMIN`.
  Usuários sem essa role recebem `403`; sem token, `401`. A infra de auth já
  existe (seed V1 + `isAdmin()` + `SimpleGrantedAuthority`).
- **Presença nos dois serviços.** O mecanismo deve existir no Assinatura e no
  Pagamento, pelo mesmo contrato.
- **Auditoria da ação.** Toda retomada registra log estruturado (evento
  `outbox_falha_retomada_manual`) com o `eventId`, o tipo de evento e o
  identificador do admin que disparou a ação, para trilha de auditoria.

### Out of Scope

- **Retomada em lote.** A retomada é pontual, por `eventId`. Lote tem risco de
  thundering herd no publisher se muitos eventos voltarem juntos; fica para além
  do desafio se a operação exigir.
- **UI/web de operação.** A interação é via API (curl, Bruno, Postman). Montagem
  de interface é tarefa de plataforma.
- **Archive/purge de eventos terminais.** Eventos que o operador decide não
  retomar continuam em `FALHA`. Política de retenção e limpeza é escopo de
  auditoria/compliance, não deste PRD.
- **Configuração do teto de ciclos automático.** O teto do BE-24 (`max-ciclos`)
  fica como está. A recuperação manual é o desfecho quando ele se esgota.
- **Migrations de banco.** A recuperação lê e atualiza a coluna `status` e o
  contador `ciclos_recuperacao` já existentes. Não há coluna nem tabela nova.

## Restrições

- **Sem quebra de contrato Kafka.** A retomada devolve o evento ao publisher
  existente, que publica no tópico já mapeado. Nada de tópico novo.
- **Configuração externalizada.** Se a listagem tiver limites (tamanho de
  página, idade mínima padrão), seguem o padrão `APP_*`.
- **Padrão de logs estruturados.** SLF4J Fluent, `event` em `snake_case`, sem
  payload nem PII, em toda nova decisão. Toda cadeia termina em `.log()`. O log
  de auditoria identifica o admin, mas não carrega credencial.
- **Concorrência segura.** O endpoint de retomada opera sob transação, usando o
  mesmo padrão de lock do publisher e do scheduler, para não duplicar a
  recuperação sob chamadas concorrentes.
- **Convenções do monorepo.** Commits PT-BR, Conventional Commits, Google Java
  Style, Javadoc obrigatório em todo símbolo público novo.

## Critérios de Aceitação

### Listagem de eventos terminais

- Dado eventos em `FALHA` na outbox, quando um admin chama o endpoint de
  listagem, então recebe a relação paginada com `eventId`, tipo de evento,
  instante da falha e contador de ciclos.
- Dado filtros aplicados (tipo de evento, idade), quando o admin consulta, então
  só os eventos que satisfazem os filtros aparecem.
- Dado um usuário sem `ROLE_ADMIN`, quando ele chama a listagem, então recebe
  `403 Forbidden`.
- Dado um usuário sem token, quando ele chama a listagem, então recebe `401
  Unauthorized`.

### Retomada por eventId

- Dado um evento em `FALHA` terminal, quando um admin chama a retomada com o
  `eventId`, então o evento transita para `RETENTATIVA_DLQ` com as tentativas
  zeradas e o contador de ciclos incrementado, devolvendo-o ao publisher.
- Dado um `eventId` inexistente, quando a retomada é chamada, então retorna
  `404 Not Found`.
- Dado um `eventId` que não está em `FALHA`, quando a retomada é chamada, então a
  operação é rejeitada com erro de estado (ex.: `409 Conflict`), sem alterar o
  evento.
- Dado um usuário sem `ROLE_ADMIN`, quando ele chama a retomada, então recebe
  `403 Forbidden`.

### Idempotência preservada

- Dado um evento retomado, quando ele é republicado e consumido, então os guards
  de idempotência existentes por `eventId` absorvem qualquer duplicata sem gerar
  efeito de negócio duas vezes.
- Dado duas chamadas concorrentes de retomada para o mesmo `eventId`, quando
  ambas avaliam, então só uma efetiva a transição, sem duplicar a recuperação.

### Presença nos dois serviços

- Dado o mecanismo presente nos dois serviços, quando um evento terminal existe
  no Pagamento, então um admin consegue listá-lo e retomá-lo pelo mesmo
  contrato do Assinatura.

### Auditoria da ação

- Dado uma retomada efetivada, quando ela ocorre, então há um log INFO com
  `event=outbox_falha_retomada_manual`, o `eventId`, o tipo de evento e o
  identificador do admin que disparou a ação.
- Dado o log de auditoria, quando um revisor o lê, então consegue reconstruir
  quem retomou qual evento e quando, sem precisar cruzar com o banco.
