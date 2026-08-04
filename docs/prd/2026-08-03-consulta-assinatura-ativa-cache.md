# PRD: Consulta de assinatura ativa com cache em Redis

**Date:** 2026-08-03
**Status:** Draft
**Contrato:** `docs/openapi/assinatura.yaml` (atualizar)
**Diagrama:** `docs/assinatura/consulta-ativa-cache-redis.puml`

## Problem

O desafio pede explicitamente cache para "consultas de assinaturas ativas". Hoje
o Assinatura Service nao tem nenhum caminho dedicado a esse fim. O cliente que
quer saber "qual e a minha assinatura corrente" so tem duas saidas, ambas
inadequadas:

- `GET /assinaturas` devolve o historico paginado em qualquer status
  (AGUARDANDO_PAGAMENTO, ATIVA, SUSPENSA, CANCELADA). A UI precisa filtrar e
  ordenar na mao para achar a ativa.
- `GET /assinaturas/{uuid}` exige que o cliente ja conheca o uuid da assinatura
  ativa, dado que ele normalmente nao tem em maos.

Além disso, o unico cache que existe hoje (BE-15) cobre a listagem completa, nao
a consulta da assinatura ativa. A tela de "minha assinatura", que e o caminho
quente de qualquer app de assinatura, fica sem leitura rapida nem cache proprio.

## Background

O BE-14 e o BE-15 (PRD `2026-08-02-listagem-assinatura-cache.md`) entregaram a
listagem paginada com cache em Redis, invalidado por versao apos o commit de
cada escrita. A infraestrutura de cache ja existe e e reutilizada aqui:
`CacheVersionado` (Redis com degradacao silenciosa), padrao cache-aside com TTL
de rede, invalidacao por contador de versao apos commit.

A regra "um usuario, uma assinatura aberta" e garantida por um indice unico
parcial no banco (`uq_assinatura_aberta_usuario`, `(usuario_id) WHERE status IN
('AGUARDANDO_PAGAMENTO','ATIVA')`). Isso significa que existe no maximo uma
assinatura ATIVA por usuario, entao a consulta da ativa e pontual, nao uma
lista. O caminho de leitura devolve zero ou uma.

O endpoint segue a ADR 0003: JWT obrigatorio, dono derivado do token, `401` para
token ausente ou invalido, `403` para autenticado sem permissao.

## Requirements

### Must Have

- `GET /assinaturas/ativa` autenticado (JWT, ADR 0003) devolve a assinatura
  ativa corrente do dono do token, nunca de outro usuario.
- Sem assinatura ATIVA (usuario novo, so AGUARDANDO_PAGAMENTO, ou so
  canceladas/suspensas), devolve `404`. Consistente com `GET /assinaturas/{uuid}`:
  o recurso "a assinatura ativa" existe ou nao existe.
- `ROLE_ADMIN` recebe `403`, igual a `GET /assinaturas`. O admin nao tem
  assinatura propria; inspecionar a ativa de um usuario arbitrario fica fora de
  escopo.
- A resposta reusa o schema da consulta pontual (`AssinaturaResponse`), sem
  criar DTO novo.
- Cache distribuido em Redis no padrao cache-aside: a chave e por usuario, com
  TTL de 5 minutos como rede de seguranca, igual a listagem.
- Invalidacao do cache apos o commit da escrita, nos mesmos fluxos que ja
  invalidam a listagem:
  - solicitacao (`POST /assinaturas`),
  - ativacao e recusa de pagamento (consumer de `PagamentoStatusAtualizado`),
  - renovacao e suspensao (consumer de resultado de renovacao),
  - varredura de vencimentos do scheduler,
  - cancelamento via HTTP.
- Degradacao silenciosa: Redis indisponivel nao derruba a consulta. O caminho
  quente cai pro banco e responde normalmente.
- A consulta reflete o estado persistido em poucos segundos apos a mudanca. A
  janela do Kafka (evento consumido -> invalidacao) tolera alguns segundos de
  atraso; no pior caso (falha de invalidacao), o dado velho expira no TTL.

### Should Have

- Logs com a Fluent API e evento estavel
  (`assinatura_ativa_consulta_cache_miss` / `_cache_hit`) para medir a taxa de
  acerto, no mesmo formato da listagem.

### Out of Scope

- Cache na consulta pontual `GET /assinaturas/{uuid}`. Continua batendo no banco
  direto, como definido no PRD da listagem.
- Consulta da ativa para administrador (`ROLE_ADMIN` com `?usuarioUuid`). Se
  surgir necessidade de suporte operacional, entra como outro PRD.
- Filtro por outros status (EM_RENOVACAO, SUSPENSA). O endpoint e exclusivo da
  assinatura ATIVA.
- Publish/update do cache no write. O padrao e invalidar, nunca atualizar o
  valor cacheado.
- Cache na regra de unicidade. A unicidade continua decidida no banco, sob
  transacao e constraint, e nunca no cache.

## Constraints

- JWT obrigatorio (ADR 0003): o dono vem do token, nunca de query param.
- O Redis e compartilhado com a sessao JWT e com o cache da listagem. O cache da
  consulta ativa precisa de namespace proprio (prefixo de chave dedicado) para
  nao colidir.
- Consistencia eventual: a ativa pode ficar defasada por segundos (janela do
  Kafka) e, no pior caso, ate o TTL de 5 minutos.
- A consulta da ativa e pontual (zero ou uma), nao paginada. O indice unico
  parcial ja garante unicidade de ATIVA por usuario.
- CQRS Lite: a consulta e uma Query nova; a invalidacao mora nos Commands e
  consumers existentes, sem introduzir Event Sourcing.

## Decisoes

- **Path.** Resolvido. `GET /assinaturas/ativa`, dono derivado do token (ADR
  0003). Rejeitado `GET /usuarios/{uuid}/assinatura-ativa` por exigir validar o
  uuid contra o token e quebrar o principio "dono do token".
- **Sem ativa.** Resolvido. `404`. Consistente com `GET /assinaturas/{uuid}`.
  Rejeitado `200 null` por ser ambiguo no contrato.
- **Admin.** Resolvido. `403`, igual a listagem. O admin nao tem assinatura
  propria; inspecionar a de outro usuario fica fora de escopo.
- **Shape da resposta.** Resolvido. Reusa o `AssinaturaResponse` da consulta
  pontual, sem DTO novo.
- **Consistencia.** Resolvido. Invalidacao no write apos o commit + TTL de 5
  minutos, igual a listagem.
- **Chave de cache.** Resolvido. Prefixo dedicado (`assinatura:ativa`) com versao
  por usuario e TTL. Reutiliza a infraestrutura de `CacheVersionado` existente.
  Detalhe do contador de versao (reusar o da listagem ou criar um proprio) e
  decisao de implementacao do roadmap.

## Acceptance Criteria

### Consulta bem-sucedida
- Given um usuario autenticado com assinatura ATIVA, when envia
  `GET /assinaturas/ativa`, then recebe `200 OK` com o schema
  `AssinaturaResponse` dessa assinatura.

### Sem assinatura ativa
- Given um usuario autenticado sem nenhuma assinatura ATIVA (novo, so
  AGUARDANDO_PAGAMENTO, ou so fechadas), when envia `GET /assinaturas/ativa`,
  then recebe `404`.

### Acesso
- Given uma requisicao sem token, when envia `GET /assinaturas/ativa`, then
  recebe `401`.
- Given um administrador autenticado, when envia `GET /assinaturas/ativa`, then
  recebe `403`.
- Given um usuario autenticado, when a consulta retorna, then nunca devolve a
  assinatura ativa de outro usuario.

### Cache
- Given uma primeira chamada `GET /assinaturas/ativa` (cache miss), when a mesma
  chamada e repetida, then a segunda responde do Redis sem consultar o banco.
- Given o Redis indisponivel, when envia `GET /assinaturas/ativa`, then a
  consulta responde normalmente consultando o banco (cache degrada, nao
  derruba).

### Invalidacao na ativacao (Kafka)
- Given a assinatura ativa do usuario em cache como AGUARDANDO_PAGAMENTO (ou
  ausente), when o pagamento e aprovado e o consumer processa
  `PagamentoStatusAtualizado`, then a proxima `GET /assinaturas/ativa` reflete
  o status ATIVA em poucos segundos.

### Invalidacao na renovacao e suspensao (Kafka)
- Given a assinatura ativa em cache, when a renovacao e aprovada, then a
  consulta reflete as novas datas de vigencia.
- Given a assinatura com tentativas esgotadas, when o consumer suspende, then a
  proxima `GET /assinaturas/ativa` devolve `404` (nao ha mais ativa).

### Invalidacao no cancelamento
- Given a assinatura ativa em cache, when o usuario solicita cancelamento e o
  fluxo encerra, then a proxima `GET /assinaturas/ativa` devolve `404`.

### TTL como rede de seguranca
- Given uma falha na invalidacao do cache, when o TTL de 5 minutos expira, then
  a proxima chamada busca do banco e repopula o cache.

## Referencias

- `docs/assinatura/consulta-ativa-cache-redis.puml`: diagrama de sequencia com o
  cache-aside e os pontos de invalidacao.
- PRD `2026-08-02-listagem-assinatura-cache.md`: origem do padrao de cache por
  versao e da infraestrutura reutilizada.
- PRD `2026-07-30-solicitacao-consulta-assinatura.md`: origem do schema
  `Assinatura` e do endpoint de consulta pontual.
- ADR 0003: JWT obrigatorio e dono derivado do token.
