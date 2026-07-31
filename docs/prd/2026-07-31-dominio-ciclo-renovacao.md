# PRD: Modelo de ciclo e renovação na assinatura

**Date:** 2026-07-31
**Status:** Draft
**Entrega:** BE-8 do roadmap `docs/roadmap/2026-07-31-renovacao-automatica.md`
(plano técnico `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`, §5
BE-8). Fundação do Stream Assinatura da renovação automática.
**Design:** `docs/renovacao/renovacao-automatica-assinatura.puml` (linhas 23-72,
165-195) e `docs/renovacao/renovacao-automatica-retry-dlq.puml`
**Branch:** `feat/dominio-renovacao`
**Versão alvo:** `0.3.0`

## Problem

A assinatura hoje vale um mês e morre. Não há modelo de ciclo nem de renovação:
nasce em `AGUARDANDO_PAGAMENTO`, vira `ATIVA` por um mês fixo
(`ProcessarPagamento` chama `ativar(hoje, hoje.plusMonths(1))`) e nenhuma peça
do sistema a estende. Quem opta por continuar cliente não tem como sinalizar
isso, quem deixa de pagar é simplesmente esquecido no vencimento, e não existe
registro histórico de quantas vezes uma assinatura foi renovada.

Sem ciclo e renovação não há receita recorrente. Não dá para saber quando cobrar
de novo, não dá para respeitar quem pediu para não renovar, e qualquer retentativa
de cobrança correria o risco de faturar o mesmo mês duas vezes. Esta track cria o
modelo de domínio e o schema que sustentam toda a renovação automática: a
assinatura ganha início e fim de ciclo, uma data de próxima renovação e a opção
de renovar ou não, e cada renovação vira um registro próprio, um por ciclo, com
unicidade que impede cobrança duplicada.

## Background

A renovação automática é a entrega central do `0.3.0`. Ela estende cada ciclo
cobrando o cliente de novo, respeita quem optou por não renovar e suspende o
acesso após três recusas seguidas (o caminho completo está no roadmap de
renovação). Esta track é a fundação de domínio do Stream Assinatura: BE-9
(scheduler de vencimento) e BE-10 (consumer de resultado) só existem em cima do
modelo criado aqui.

O agregado `Assinatura` hoje (`Assinatura.java`) é uma entidade JPA com campos
planos de adesão (`dataInicio`, `dataExpiracao`, `status`) e dois métodos de
domínio que seguem um padrão de guarda com early-return silencioso (`ativar`,
`recusarPagamento`). O `StatusAssinatura` tem três valores
(`AGUARDANDO_PAGAMENTO`, `ATIVA`, `PAGAMENTO_RECUSADO`) e não representa os
estados intermediários do fluxo de renovação: uma assinatura esperando o
pagamento da renovação, suspensa por recusa recorrente ou cancelada por opt-out.

A decisão D-R7 do plano fixa o desenho: a `Assinatura` ganha campos de ciclo e
novos status, e a renovação é um agregado novo (`Renovacao`) em vez de criar uma
`Assinatura` por ciclo. A decisão D-R5 justifica esse formato: criar uma nova
`Assinatura` a cada ciclo chocaria com o índice único parcial
`uq_assinatura_aberta_usuario` (uma assinatura aberta por usuário), então a
renovação estende a `Assinatura` existente e registra cada ciclo numa linha de
`renovacao`. A unicidade `(assinatura_id, ciclo_referencia)` é o que evita cobrar
duas vezes o mesmo mês.

## Requirements

### Must Have

- A `Assinatura` ganha os campos de ciclo: `inicioCiclo`, `fimCiclo`,
  `proximaRenovacaoEm` e `renovacaoAutomatica`. Eles convivem com os campos de
  adesão existentes (`dataInicio`, `dataExpiracao`), sem substituí-los.
- `renovacaoAutomatica` nasce `true` por padrão (opt-out), decisão O-R2 do plano.
- O `StatusAssinatura` ganha `EM_RENOVACAO`, `SUSPENSA` e `CANCELADA`,
  representando os estados intermediários e finais do fluxo de renovação.
- A entidade `Renovacao` representa um ciclo de renovação, com status
  (`PENDENTE`, `APROVADA`, `TENTATIVAS_ESGOTADA`), factory e transições de
  domínio. Cada renovação pertence a uma `Assinatura` e referencia um ciclo.
- A migration `V7` adiciona as colunas de ciclo em `assinatura` e cria a tabela
  `renovacao` com a restrição `UNIQUE (assinatura_id, ciclo_referencia)`, que é o
  que blinda contra cobrança duplicada do mesmo ciclo.
- O método `Assinatura.renovar(novoFimCiclo)` rola o ciclo para frente: partindo
  de `ATIVA` ou `EM_RENOVACAO`, define `inicioCiclo` como o `fimCiclo` anterior,
  recalcula `fimCiclo` e `proximaRenovacaoEm`, e volta a `ATIVA`.
- O método `Assinatura.suspender()` leva a `SUSPENSA`, partindo de `EM_RENOVACAO`.
- O método `ativar()` existente não muda. A ativação inicial define o primeiro
  ciclo; a renovação rola o ciclo adiante. São caminhos distintos.

### Should Have

- Transições de estado ilegais são rejeitadas de forma explícita (não ignoradas
  silenciosamente como o `ativar`/`recusarPagamento` de hoje), de modo que um
  `renovar` ou `suspender` fora de estado deixe o problema visível.

### Out of Scope

- O scheduler de vencimento que cria `Renovacao` e publica `RenovacaoSolicitada`
  (BE-9). Esta track entrega o domínio e o schema que o scheduler vai usar, mas
  não dispara renovação.
- O consumer de resultado da renovação que chama `renovar`/`suspender` e publica
  `AssinaturaRenovada`/`AssinaturaSuspensa` (BE-10).
- A roteação de eventos da outbox por `eventType` (BE-7), já que esta track não
  publica nenhum evento novo.
- O agregado de pagamento da renovação e toda a Stream Pagamento (BE-11, BE-12,
  BE-13).
- Um endpoint de cancelamento ad hoc pelo cliente (decisão O-R5: a renovação
  trata só `renovacao_automatica = false` levando a `CANCELADA` no vencimento;
  cancelamento explícito fica para roadmap futuro).
- A reconciliação de cobranças `PENDING` abandonadas (decisão O-R4, fora de
  escopo da renovação).

## Constraints

- O schema é controlado por Flyway com `ddl-auto: validate`. A migration `V7`
  deve ser aditiva (novas colunas com default, nova tabela) e reversível sem
  perder dados de adesão. A numeração `V7` está coordenada no plano (Assinatura
  termina em `V6`; BE-10 criará `V8`).
- Conflito conhecido com o índice único parcial `uq_assinatura_aberta_usuario`
  (`ON assinatura(usuario_id) WHERE status IN ('AGUARDANDO_PAGAMENTO','ATIVA')`,
  migration V3). O design de renovação extende a assinatura existente justamente
  para não chocar com esse índice; `EM_RENOVACAO` precisa ser tratado com cuidado
  em relação aos "status abertos" usados por `SolicitarAssinatura`.
- Convenções do projeto: Javadoc em todo tipo/método público (primeira frase,
  depois `@param`/`@return`/`@throws`, `{@code}` para literais), Google Java
  Style, `make verify` verde (só unitário; integração via `make test-integration`
  ao fim).
- Testes no padrão do projeto: JUnit 5, `@DisplayName`, AssertJ com `.as(...)`
  contextual, transições válidas e rejeições cobertas.

## Decisoes

- **Renovação é agregado novo, não nova `Assinatura`.** Resolvido (D-R5). Criar
  uma `Assinatura` por ciclo chocaria com `uq_assinatura_aberta_usuario`. A
  renovação estende a `Assinatura` existente e registra cada ciclo numa linha de
  `renovacao`, com `UNIQUE (assinatura_id, ciclo_referencia)` evitando cobrança
  duplicada.
- **`Assinatura` ganha campos de ciclo e novos status.** Resolvido (D-R7).
  Campos `inicioCiclo`, `fimCiclo`, `proximaRenovacaoEm`, `renovacaoAutomatica`.
  Status `EM_RENOVACAO`, `SUSPENSA`, `CANCELADA`. `renovar()` distinto de
  `ativar()`: a ativação inicial define o primeiro ciclo; a renovação rola o
  ciclo adiante.
- **Default de `renovacaoAutomatica` é `true` (opt-out).** Resolvido como default
  (O-R2), marcado para confirmação de produto. Uma assinatura nasce apta a
  renovar; quem não quer explicita isso.
- **`ativar()` existente não muda.** Resolvido (plano DoD). O fluxo de adesão
  permanece intacto; o primeiro ciclo é definido pela ativação, os seguintes
  pelas renovações.

- **A ativação inicial define o primeiro ciclo.** Resolvido (default da
  implementacao). O plano diz "`ativar()` nao muda" e tambem "a ativacao inicial
  define o primeiro ciclo". Estas duas afirmacoes entram em conflito se lidas ao
  pe da letra: para `renovar()` rolar `inicioCiclo = fimCiclo anterior`, o
  primeiro ciclo precisa de `fimCiclo` definido desde a adesao. Decisao: o guard de
  idempotencia de `ativar()` (early-return quando ja `ATIVA`) e os seus parametros
  (`dataInicio`, `dataExpiracao`) nao mudam, mas a transicao
  `AGUARDANDO_PAGAMENTO -> ATIVA` popula tambem `inicioCiclo = dataInicio`,
  `fimCiclo = dataExpiracao` e `proximaRenovacaoEm = dataExpiracao`. Assim o
  modelo de ciclo e coerente desde a adesao e `renovar()` nunca trata `null`. Os
  testes existentes de ativacao seguem verde (o comportamento observavel por eles
  nao muda).

- **A transição para `EM_RENOVACAO` é um método do agregado.** Resolvido
  (decisao de implementacao, confirmada). O DoD do plano diz "`suspender()` parte de
  `EM_RENOVACAO`", mas nao fixa quem leva a assinatura a `EM_RENOVACAO`. Pelo design,
  quem dispara essa transicao e o scheduler (BE-9), junto do `INSERT renovacao` e da
  outbox. Como o agregado `Assinatura` centraliza todas as transicoes de status em
  metodos proprios (`ativar`, `recusarPagamento`) e o `ProcessarPagamento` delega a
  eles, BE-8 adiciona o metodo de dominio `iniciarRenovacao()` que leva
  `ATIVA -> EM_RENOVACAO`. O scheduler BE-9 o chamara, mantendo o encapsulamento e
  tornando `suspender()` testavel sem reflexao.

- **A constante `CANCELADA` entra nesta track.** Resolvido (confirmado). Mesmo sem
  transicao de agregado que a alcance nesta track (o scheduler BE-9 fara
  `UPDATE status = CANCELADA` quando `renovacao_automatica = false`), a constante
  entra no enum para refletir o design completo e permitir que a migration V7 e o
  indice a referenciem. Sem teste comportamental proprio nesta track.

- **Guards de transicao invalida nos novos metodos.** Resolvido (confirmado).
  Os metodos novos (`renovar`, `iniciarRenovacao`, `suspender`, `aprovar`,
  `esgotarTentativas`) rejeitam transicoes invalidas de forma explicita (lanca
  excecao de dominio), em vez do early-return silencioso de `ativar`/
  `recusarPagamento`. Cada guard tem seu proprio teste RED. Observacao: isto cria
  assimetria com os metodos de adesao existentes; alinhar esses ao mesmo padrao
  fica fora de escopo (mudanca de comportamento de adesao). Foram cobertos nesta
  track os guards de maior risco: `suspender()` de `ATIVA`, re-aplicar `aprovar()`
  e re-aplicar `esgotarTentativas()` (idempotencia dos estados terminais do
  `Renovacao`). Os guards de transicoes cruzadas restantes (ex.: `aprovar()` de
  `TENTATIVAS_ESGOTADA`, `renovar`/`iniciarRenovacao` de estados invalidos) ficam
  como follow-up, mesmo padrao (um RED por estado ilegal).

## Decisoes abertas (defaults assumidos, confirmar)

- **Tipo de `ciclo_referencia` (default assumido).** Resolvido como `LocalDate`
  representando o fim do ciclo que esta sendo renovado (ex.: `2026-02-01`).
  Justificativa: a unicidade e "uma renovacao por vencimento", segue o padrao de
  datas do agregado `Assinatura` (`inicioCiclo`/`fimCiclo` sao `LocalDate`) e e
  trivial de derivar no scheduler (`proximaRenovacaoEm`). Confirmar ao implementar
  BE-9.

- **`EM_RENOVACAO` conta como "status aberto"?** O índice parcial
  `uq_assinatura_aberta_usuario` e a consulta `existsByUsuarioIdAndStatusIn` em
  `SolicitarAssinatura` tratam `AGUARDANDO_PAGAMENTO` + `ATIVA` como abertos.
  Default: `EM_RENOVACAO` não entra em "status aberto" (uma assinatura em
  renovação não abre uma nova solicitação concorrente), mas isso não é decidido
  nesta track de domínio. Deixa-se para BE-9, que introduz o estado
  `EM_RENOVACAO` em uso, confirmar o tratamento no índice/consulta.
- **Tipo de `ciclo_referencia`.** Default: um identificador de ciclo que permita
  unicidade `(assinatura_id, ciclo_referencia)` (ex.: mês/ano do vencimento).
  Confirmar a representação exata ao implementar BE-9, que é quem cria
  `Renovacao`.
- **Rejeição explícita de transições ilegais.** Default (Should Have): `renovar`
  e `suspender` fora de estado lançam exceção de domínio em vez de early-return
  silencioso. Avaliar se vale alinhar `ativar`/`recusarPagamento` ao mesmo
  padrão, mas isso é mudança de comportamento de adesão e fica fora desta track.

## Acceptance Criteria

### Assinatura ganha campos de ciclo
- Given a migration atual é `V6`, when a track entrega, then a migration `V7`
  adiciona em `assinatura` as colunas `inicio_ciclo`, `fim_ciclo`,
  `proxima_renovacao_em` e `renovacao_automatica` (default `true`), sem remover
  nem alterar as colunas de adesão existentes.

### Tabela de renovação com unicidade por ciclo
- Given a migration `V7` aplica, then a tabela `renovacao` existe com a restrição
  `UNIQUE (assinatura_id, ciclo_referencia)`, de modo que duas renovações do
  mesmo ciclo da mesma assinatura não podem coexistir.

### Status novos representam o fluxo de renovação
- Given o `StatusAssinatura`, when a track entrega, then os valores
  `EM_RENOVACAO`, `SUSPENSA` e `CANCELADA` existem ao lado dos três existentes.

### Renovação rola o ciclo adiante
- Given uma `Assinatura` `ATIVA` com `fimCiclo` conhecido, when `renovar` é
  chamado com um novo `fimCiclo`, then `inicioCiclo` assume o `fimCiclo` anterior,
  `fimCiclo` e `proximaRenovacaoEm` são recalculados, e a assinatura volta a
  `ATIVA`.

### Suspensão parte de renovação em curso
- Given uma `Assinatura` `EM_RENOVACAO`, when `suspender` é chamado, then a
  assinatura vai a `SUSPENSA`.

### Ativação inicial preservada
- Given o fluxo de adesão existente, when `ProcessarPagamento` ativa uma
  assinatura, then `ativar` define o primeiro ciclo exatamente como antes e o
  teste `AssinaturaTest` de ativação segue verde, sem mudança de comportamento.

### Agregado Renovação com transições controladas
- Given uma `Renovacao` nova, when é criada, then nasce `PENDENTE`; when recebe
  aprovação, then vai a `APROVADA`; when esgota as tentativas, then vai a
  `TENTATIVAS_ESGOTADA`.
