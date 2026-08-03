# Code Review: `feat/consulta-assinatura-ativa-cache`

**Data:** 2026-08-03
**Branch:** `feat/consulta-assinatura-ativa-cache` (worktree `.worktrees/feat-consulta-assinatura-ativa-cache`, base `develop` @ `33813df`)
**Entregável:** BE-16/BE-17. `GET /assinaturas/ativa` com cache-aside em Redis, cache negativo e contador de versão compartilhado com a listagem.
**Diff:** 19 arquivos, +1350/−15 (14 commits).
**Reviewers:** security, performance, quality + red-team audit.

## Escopo

Endpoint `GET /assinaturas/ativa` autenticado (JWT, dono do token) que devolve a assinatura ATIVA do
usuário como `AssinaturaResponse`, com `404` sem ativa e `403` para `ROLE_ADMIN`. Cache-aside em Redis
por usuário (TTL 5 min, cache negativo da ausência como literal JSON `"null"`), reusando o contador de
versão da listagem (`assinatura:list:versao:{uuid}`) para invalidação. Mirror fiel do padrão
`AssinaturaListaCache`: mesma estrutura, mesmas primitivas de `CacheVersionado`, mesmos eventos de log
(`assinatura_ativa_cache_*`).

## Veredito

**Ship it.** Sem critical, sem high, sem medium introduzidos por este PR. Os high/medium que apareceram
na revisão de performance são todos pré-existentes (copiados da listagem ou anteriores a ela), trade-offs
documentados (D-A4), ou gaps de DB fora de escopo (`usuarios.uuid`). A auditoria confirmou e rebaixou. A
superfície de segurança é limpa (sem IDOR, sem colisão de rota, sem leak cross-user, sem PII em log) e os
testes cobrem o tri-state do cache, a invalidação pelo contador compartilhado e o 401/403/404 end-to-end.
Os dois findings low foram endereçados antes do merge (ver [Resolvido](#resolvido)).

## Critical

Nenhum.

## High

Nenhum (introduzido pelo PR).

## Medium

Nenhum (introduzido pelo PR).

## Low

### [security] Guard admin checa `== null`, não blank — **resolvido**

`services/assinatura/src/main/java/com/globo/assinatura/consulta/api/ConsultaAssinaturaAtivaController.java:38`

O guard é `if (principal.usuarioId() == null) throw new AcessoNegadoException();`. O claim `usuarioId`
só é adicionado ao JWT quando não-nulo (`JwtService.java:65-67`), então um claim vazio (`""`) seria
estampado, relido como `""` e passaria o guard (404 em vez de 403). `JwtAuthenticationFilter` só valida
`role`, não `usuarioId`. **Defesa-em-profundidade apenas**: tokens são HS256 assinados pelo servidor,
atacante não forja. O gatilho realista é um bug futuro no login que passe `""` em vez de `null`.

- **Test (RED first):** emitir token via `JwtService.generateToken("x", "ROLE_CLIENT", "")`, setar como
  `AuthenticationPrincipal`, chamar `GET /assinaturas/ativa`, assertar 403. Hoje retorna 404.
- **Fix:** `if (principal.usuarioId() == null || principal.usuarioId().isBlank())`. Mesmo issue
  pré-existe em `ListaAssinaturasController.java:44`; endurecer os dois no mesmo commit.

### [quality] FQN `org.mockito.ArgumentMatchers.any()` em vez de static import — **resolvido**

`services/assinatura/src/test/java/com/globo/assinatura/consulta/api/ConsultaAssinaturaAtivaControllerTest.java:105`

O sibling `ConsultaAssinaturaAtivaTest.java:4` usa `import static org.mockito.ArgumentMatchers.any;` e
chama `any()` sem qualificar. O novo teste escreve `org.mockito.ArgumentMatchers.any()` inline. Não falha
Checkstyle/Spotless (FQN é Java válido), mas diverge do estilo estabelecido no próprio pacote.

- **Fix:** adicionar o static import e remover a qualificação. Nit puro.

## Good patterns

- **Sem IDOR**: nenhum identificador controlado pelo usuário (sem path var, sem query param). A chave de
  lookup é exclusivamente `principal.usuarioId()` (`ConsultaAssinaturaAtivaController.java:42`), vinda do
  JWT assinado. `ConsultarAssinaturaAtiva.executar` e `AssinaturaAtivaCache.chaveDe` indexam por esse
  mesmo valor ponta a ponta.
- **Cache negativo correto**: a ausência é cacheada como literal JSON `"null"` e relida como
  `Optional.of(Optional.ofNullable(null))` → `Optional.of(Optional.empty())`, distinguível de miss
  (`AssinaturaAtivaCache.java:70-77`). O tri-state (`Optional<Optional<AssinaturaResponse>>`) é
  documentado no Javadoc de `recuperar` e reexplicado no call site em `executar`. Rejeição de interface
  selada justificada (um único consumidor).
- **Query limpa de Security**: `ConsultarAssinaturaAtiva.executar(String usuarioUuid)` recebe primitivo,
  não `UsuarioAutenticado`. Melhoria de fronteira CQRS sobre o `ConsultarAssinatura` antigo, que vaza
  Spring Security para a query. O 403 ficou no controller, onde pertence.
- **Rota literal sem colisão**: `/ativa` (literal) vence `/{uuid}` (variável) no PathPatternParser
  (default do Spring Boot 4.1) independentemente da ordem de declaração. Provado por testes de controller
  e integração.
- **Contador compartilhado documentado e testado**: a reusagem do contador da listagem (D-A4) invalida a
  ativa junto com zero mudança nos 5 fluxos de escrita. O acoplamento é explicitado no Javadoc de classe
  de `AssinaturaAtivaCache` e provado por `ConsultarAssinaturaAtivaIntegracaoTest.deveInvalidarCacheAposSolicitarAssinatura`
  (v0 absence → solicit → contador=1 → repopula v1).
- **Teste 403 asserta que a query nunca é chamada** (`ConsultaAssinaturaAtivaControllerTest.java:105`),
  pinando que o guard curto-circuita antes de qualquer acesso a dado.
- **Teste 401 de contexto completo** (`ConsultaAssinaturaAtivaSemTokenTest`) sobe o filtro de segurança
  real e dispara `HttpClient` do JDK sem credenciais. O sibling da listagem nem tem esse teste.
- **Mirror fiel da listagem**: mesmos helpers `chaveDe`/`versaoAtual`, mesmo tratamento de
  `JacksonException` (WARN + miss), mesma degradação silenciosa do `CacheVersionado`, logs Fluent com
  `event` snake_case, `addKeyValue("usuarioId", uuid)`, mensagens curtas sem interpolação, toda cadeia
  terminando em `.log()`, severidades certas (INFO miss/hit, WARN json ilegível/população falhou).
- **Javadoc completo** em todo tipo e método público novo, abrindo com frase descritiva, `{@code ...}`
  para literais, `@param`/`@return`/`@throws` com condição disparadora.
- **Sem `log-and-throw`**: `JacksonException` engolido com WARN em `recuperar` e `popular`, consistente
  com `AssinaturaListaCache`.

## Audit notes

- **Falso positivo — Performance [HIGH] "GET redundante do contador a cada hit"**: `AssinaturaAtivaCache`
  é mirror linha-a-linha de `AssinaturaListaCache` (mesma releitura de `versaoAtual`, mesmo shape de 2
  GETs no hit). O PR não inventou o padrão, copiou deliberadamente o de referência. Um GET extra (~0.1ms
  local) num cache por usuário com TTL de 5 min e alta taxa de acerto é micro-otimização. Rebaixado a
  Info. Item de follow-up contra o padrão compartilhado, não bloqueante.
- **Falso positivo — Performance [HIGH] "`usuarios.uuid` sem índice"**: confirmado pré-existente. V2 cria
  `uq_usuarios_email` sobre `email`, mas nenhum índice sobre `uuid`. `findByUuid` faz seq scan e é usado
  por 4 callers (`ConsultarAssinatura`, `ListarAssinaturas`, `SolicitarAssinatura`, e agora este). O PR é
  o 4º caller; não piora nem melhora. Achado de DB sistêmico válido, venue errada. Issue separada.
- **Trade-off aceito e documentado — Performance [MEDIUM] "contador compartilhado invalida em escritas
  cross-status" (D-A4)**: uma `SolicitarAssinatura` (vira `AGUARDANDO_PAGAMENTO`, não `ATIVA`) invalida
  um cache negativo correto, forçando re-miss. Explicitamente aceito no PRD/roadmap ("falso positivo de
  invalidação é inofensivo em cache-aside") e pinado por teste de integração. Não é finding.
- **Correção de isolation — Performance [MEDIUM] "race de versão no `popular`"**: o reviewer assumiu
  REPEATABLE READ. O default do Postgres é **READ COMMITTED** (nenhum override de `isolation` em
  `src/main/java`). Sob READ COMMITTED, cada statement de `buscarNoBanco` (`findByUuid` →
  `findByUsuarioIdAndStatus`) tira snapshot fresco, o que **estreita** a janela: só há stale-write se um
  commit landar **após o último statement** de `buscarNoBanco` e **antes da releitura do contador** em
  `popular`. Real, mas pré-existente (idêntico em `ListarAssinaturas`), estreito, TTL-bounded (≤300s),
  self-healing no próximo bump. Rebaixado a Low para este PR. Qualquer fix (resolver versão uma vez e
  passá-la para buscar e popular) deve tocar **ambos** os caches num refactor separado, começando por um
  RED de concorrência que demonstre stale-write-under-new-version.
- **Não-bug verificado — `versaoAtual` default `0`**: usuário novo sem escritas não tem chave de
  contador; `.orElse("0")` retorna `0`, primeiro miss escreve `v0:{uuid}`, primeira escrita bumpa para
  `1` evictando `v0`. Semântica de estado inicial correta.
- **Não-bug verificado — cache negativo "usuário não existe" vs "sem ativa"**: ambos escrevem `null` sob
  a mesma chave `assinatura:ativa:v{N}:{uuid}`. A chave é namespaced pelo UUID do próprio dono (do
  token); JWT válido implica usuário existente. O branch "usuário não existe" é defensivo (deleção
  pós-login). Sem leak cross-user.
- **Não-flaky verificado — asserção de TTL `isBetween(1L, 300L)`**: `SET ... EX 300` seguido de
  `getExpire` retorna ~299; o bound inferior 1 exigiria ~299s de runtime de teste para falhar. Seguro.
- **Justificado — `ConsultaAssinaturaAtivaSemTokenTest` como `@SpringBootTest`**: o sibling da listagem
  não tem teste de 401. Este prova o filtro de segurança real ponta a ponta via JDK `HttpClient`. Está
  taggeado `@Tag("integration")`, excluído do `make verify`. Cobertura melhor que a do sibling, não
  redundante.

## Plano de ação sugerido (antes do merge)

Ambos os itens foram implementados neste PR (TDD, RED first), ver [Resolvido](#resolvido).

## Resolvido

- **Guard admin com `isBlank()`**: `ConsultaAssinaturaAtivaController:38` e `ListaAssinaturasController:44`
  agora rejeitam `usuarioId` nulo **ou vazio** com `403`. Testes RED
  `deveRetornarForbiddenQuandoUsuarioIdVazio` nos dois controllers de consulta (antes: 404/200, depois:
  403 com a query nunca chamada).
- **Static import de `any()`**: `ConsultaAssinaturaAtivaControllerTest` passa a usar
  `import static org.mockito.ArgumentMatchers.any;`, alinhado ao sibling do pacote.
- **Dedup de `toResponse`**: as 3 cópias idênticas (`ConsultarAssinaturaAtiva`, `ListarAssinaturas`,
  `ConsultarAssinatura`) viraram o factory `AssinaturaResponse.of(Assinatura, String usuarioUuid)` no
  próprio record.

## Acompanhamento

- **Índice em `usuarios.uuid`** (sistêmico, maior impacto de DB): `CREATE UNIQUE INDEX uq_usuarios_uuid
  ON usuarios (uuid);` numa migration nova. Afeta 4 callers de `findByUuid`. Issue separada.
- **Race de versão no `popular`** (pré-existente na listagem): resolver o contador uma vez por chamada em
  `executar` e repassar para `buscarNoBanco`/`popular` em **ambos** `AssinaturaAtivaCache` e
  `AssinaturaListaCache`. RED first: teste de concorrência demonstrando stale-write-under-new-version.
- **Pinar branch "usuário não existe"**: `ConsultarAssinaturaAtivaTest` não cobre `findByUuid` → vazio
  (coberto por composição + integração). Teste unitário `devePopularAusenciaQuandoUsuarioNaoExiste`
  travaria o branch contra refactor de `buscarNoBanco`.
