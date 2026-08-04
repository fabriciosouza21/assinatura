# PRD: Listagem de assinaturas com cache em Redis

**Date:** 2026-08-02
**Status:** Draft
**Contrato:** `docs/openapi/assinatura.yaml` (atualizar)
**Diagrama:** `docs/assinatura/listagem-cache-redis.puml`

## Problem

O cliente consegue solicitar uma assinatura e consultar uma a uma pelo uuid,
mas nao tem nenhuma visao do conjunto: quantas assinaturas tem, quais estao
ativas, quais foram canceladas. Toda tela de "minhas assinaturas" fica
bloqueada sem esse endpoint. Alem disso, consultas repetidas (o cliente abre a
tela de assinaturas varias vezes) batem no banco a cada chamada, sem nenhuma
camada de cache para aliviar a leitura.

## Background

O BE-2 (PRD `2026-07-30-solicitacao-consulta-assinatura.md`) entregou apenas
`POST /assinaturas` e `GET /assinaturas/{uuid}`, e recortou explicitamente a
listagem do escopo. Desde entao o dominio evoluiu: a assinatura hoje percorre
AGUARDANDO_PAGAMENTO, ATIVA, EM_RENOVACAO, SUSPENSA e CANCELADA (scheduler de
renovacao e consumers de pagamento), entao o historico por usuario ja tem
varios status possiveis de listar.

O Redis ja existe na infraestrutura (`docker-compose.yml`), mas no Assinatura
Service e usado apenas para sessao JWT (`spring-boot-starter-session-data-redis`).
Cache de dados e uso novo.

A regra "um usuario, uma assinatura aberta" limita o tamanho do historico:
cada usuario acumula no maximo uma assinatura aberta por vez, e as fechadas
(canceladas) crescem devagar. Ainda assim, paginar foi decidido para a UI
crescer sem surpresa.

## Requirements

### Must Have

- `GET /assinaturas` autenticado (JWT, ADR 0003) devolve as assinaturas do
  usuario dono do token, nunca de outro usuario.
- Paginacao via query params `page` e `size`: `page` comeca em 0, `size` tem
  default 20 e maximo 100. Resposta: `items` + `page` + `size` + `total`.
- Ordenacao da mais recente para a mais antiga (dataInicio desc, com fallback
  para a mais recentemente criada).
- Cada item da lista usa o mesmo schema da consulta pontual: id, usuarioId,
  plano, dataInicio, dataExpiracao e status.
- Usuario sem assinaturas recebe `200 OK` com lista vazia e `total` zero (nao
  `404`).
- Cache distribuido em Redis no padrao cache-aside: a chave e por usuario e
  pagina, com TTL de 5 minutos como rede de seguranca.
- Invalidação do cache apos o commit da escrita (invalidate-on-write), em
  todos os fluxos que alteram assinaturas:
  - solicitacao (`POST /assinaturas`),
  - ativacao e recusa de pagamento (consumer de `PagamentoStatusAtualizado`),
  - renovacao e suspensao (consumer de resultado de renovacao),
  - varredura de vencimentos do scheduler (criacao de renovacao e cancelamento
    por opt-out),
  - futuro cancelamento via HTTP, quando implementado.
- A listagem reflete o estado persistido em poucos segundos apos a mudanca. A
  janela do Kafka (evento consumido -> invalidação) tolera alguns segundos de
  atraso; no pior caso (falha de invalidação), o dado velho expira no TTL.

### Should Have

- Indice de banco para a query de listagem por usuario com ordenacao, em nova
  migration (schema Flyway-owned, `ddl-auto: validate`).
- Logs com a Fluent API e evento estavel (`assinatura_lista_consulta_cache_miss`
  / `_cache_hit`) para medir a taxa de acerto do cache.

### Out of Scope

- Cache na consulta pontual `GET /assinaturas/{uuid}`. O padrao pode ser
  replicado depois, mas este PRD cobre apenas a listagem.
- Listagem para administrador (todas as assinaturas do sistema). O
  administrador nao tem `usuarioId` no token e recebe `403`, igual a
  solicitacao.
- Cache na regra "uma assinatura aberta". A unicidade continua decidida no
  banco, sob transacao e constraint, e nunca no cache.
- Cache nas leituras transacionais dos consumers (idempotencia, lock
  pessimista). Elas continuam lendo o banco.
- Filtros por status ou plano na listagem.
- Publish/update do cache no write. O padrao e invalidar (DEL), nunca
  atualizar o valor cacheado.

## Constraints

- JWT obrigatorio (ADR 0003): o dono vem do token, nunca de query param.
- O Redis e compartilhado com a sessao JWT no Assinatura Service. O cache de
  dados precisa de namespace proprio (prefixo de chave dedicado) para nao
  colidir com as sessoes.
- Consistencia eventual: a lista pode ficar defasada por segundos (janela do
  Kafka) e, no pior caso, ate o TTL de 5 minutos.
- Paginacao e cache interagem: a chave de cache inclui a pagina, e a
  invalidação remove todas as paginas do usuario, nao so a primeira.
- CQRS Lite: a listagem e uma Query nova; a invalidação mora nos Commands e
  consumers existentes, sem introduzir Event Sourcing.

## Decisoes

- **Paginacao.** Resolvido. `GET /assinaturas?page=0&size=20`, com `size`
  maximo 100 e resposta paginada (`items`, `page`, `size`, `total`). O
  historico por usuario e pequeno, mas a UI fica preparada para crescer.
- **Shape da resposta.** Resolvido. Cada item reusa o schema `Assinatura` da
  consulta pontual (6 campos). Campos como `renovacaoAutomatica` entram junto
  com o cancelamento agendado, em outro PRD, sem quebrar este contrato.
- **Consistencia.** Resolvido. Invalidação no write apos o commit + TTL de
  5 minutos. A listagem tolera segundos de atraso nos fluxos assincronos.
- **Chave de cache.** Resolvido. `assinatura:list:{usuarioUuid}:{page}` com
  TTL. A invalidação remove as chaves do usuario (detalhe de implementacao no
  roadmap).

## Acceptance Criteria

### Listagem bem-sucedida
- Given um usuario autenticado com assinaturas, when envia `GET /assinaturas`,
  then recebe `200 OK` com `items` ordenados da mais recente para a mais
  antiga, respeitando `page` e `size`, e `total` igual a quantidade real.

### Sem assinaturas
- Given um usuario autenticado sem assinaturas, when envia `GET /assinaturas`,
  then recebe `200 OK` com `items` vazio e `total` zero.

### Paginacao
- Given um usuario com mais assinaturas que o `size`, when pagina na pagina 0,
  then recebe os primeiros `size` itens; quando pagina na ultima pagina, then
  recebe os itens restantes; quando pagina alem do total, then recebe `items`
  vazio com `total` preservado.

### Acesso
- Given uma requisicao sem token, when envia `GET /assinaturas`, then recebe
  `401`.
- Given um administrador (sem usuario de dominio), when envia `GET
  /assinaturas`, then recebe `403`.
- Given um usuario autenticado, when a lista e carregada, then nunca contem
  assinaturas de outro usuario.

### Cache
- Given uma primeira chamada `GET /assinaturas` (cache miss), when a mesma
  chamada e repetida, then a segunda responde do Redis sem consultar o banco.
- Given o Redis indisponivel, when envia `GET /assinaturas`, then a listagem
  responde normalmente consultando o banco (cache degrada, nao derruba).

### Invalidação na solicitacao
- Given a lista do usuario em cache, when envia `POST /assinaturas` e recebe
  `202`, then a proxima `GET /assinaturas` inclui a nova assinatura.

### Invalidação na ativacao (Kafka)
- Given uma assinatura AGUARDANDO_PAGAMENTO na lista cacheada, when o
  pagamento e aprovado e o consumer processa `PagamentoStatusAtualizado`,
  then a lista reflete o status ATIVA em poucos segundos.

### Invalidação na renovacao e suspensao (Kafka)
- Given uma assinatura ATIVA na lista cacheada, when a renovacao e aprovada,
  then a lista reflete as novas datas de vigencia.
- Given uma assinatura com tentativas esgotadas, when o consumer suspende,
  then a lista reflete SUSPENSA.

### Invalidação na varredura de vencimentos
- Given uma assinatura ATIVA com renovacao automatica desativada, when o
  scheduler executa a varredura e cancela, then a lista reflete CANCELADA.

### TTL como rede de seguranca
- Given uma falha na invalidação do cache, when o TTL de 5 minutos expira,
  then a proxima chamada busca do banco e repopula o cache.

## Referencias

- `docs/assinatura/listagem-cache-redis.puml`: diagrama de sequencia com o
  cache-aside e os pontos de invalidação.
- PRD `2026-07-30-solicitacao-consulta-assinatura.md`: origem dos endpoints e
  do schema `Assinatura`.
