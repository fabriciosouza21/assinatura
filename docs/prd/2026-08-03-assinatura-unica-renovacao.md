# PRD: Conformidade da assinatura única durante a renovação

**Data:** 2026-08-03
**Status:** Draft
**Item de roadmap:** BE-23
**Branch:** `feat/assinatura-unica-renovacao`
**Serviço:** `services/assinatura`

## Problema

A regra de negócio central do sistema diz que um usuário só pode ter uma
assinatura "aberta" por vez. Hoje essa regra protege apenas os status
`AGUARDANDO_PAGAMENTO` e `ATIVA`. Quando uma assinatura entra em renovação
automática (`EM_RENOVACAO`), a janela deixa de ser coberta e o usuário
consegue iniciar uma segunda assinatura durante a cobrança do ciclo que está
encerrando.

O impacto é pequeno em volume, mas sério em consistência. O sistema cria uma
segunda assinatura enquanto a primeira ainda pode ser aprovada, resultando em
dois contratos ativos para o mesmo usuário e cobranças duplicadas. É uma
fresta na invariante mais importante do domínio, exatamente no momento em que
o dinheiro está sendo movido.

## Background

O ciclo de vida de uma assinatura passa por vários estados: após a adesão ela
fica `AGUARDANDO_PAGAMENTO`, vira `ATIVA` quando o pagamento é confirmado e, no
vencimento, transita para `EM_RENOVACAO` enquanto o gateway processa a nova
cobrança. Se a cobrança for aprovada, o ciclo rola e ela volta a `ATIVA`. Se
falhar três vezes, ela é suspensa.

A proteção contra assinatura duplicada vive em dois lugares:

- **Índice único parcial** no banco de dados, que impede fisicamente dois
  registros abertos para o mesmo `usuario_id` nos status cobertos.
- **Verificação de negócio** no comando de solicitação, que rejeita a criação
  com HTTP 409 antes mesmo de tentar gravar, devolvendo o mesmo erro que o
  índice.

Ambas as travas foram desenhadas para os status iniciais e esqueceram de
`EM_RENOVACAO`. O enunciado do desafio é explícito: a regra precisa valer
durante toda a vida útil do contrato, inclusive no momento da renovação.

## Requirements

### Must Have

- Um usuário com assinatura `EM_RENOVACAO` não pode criar uma nova assinatura.
  A solicitação recebe o mesmo `409 Conflict` já devolvido para os demais
  status abertos, com o mesmo contrato de erro, sem mudança de payload.
- O índice único do banco passa a cobrir `EM_RENOVACAO`, fechando a janela de
  concorrência entre a checagem de negócio e o insert (race condition).
- A rejeição ocorre antes de qualquer publicação de `AssinaturaSolicitada`,
  evitando que o Pagamento Service inicie uma cobrança que nunca deveria
  existir.

### Should Have

- Testes que pinem explicitamente o conjunto de status considerados "abertos",
  para que a regressão (remoção futura de `EM_RENOVACAO` da lista) seja pega
  de imediato. Hoje os testes existentes usam matchers genéricos e não
  blindam o conjunto.

### Out of Scope

- Inclusão de `PAGAMENTO_RECUSADO` no conjunto de status abertos. Esse estado
  é transitório entre a recusa da adesão e o destino final (ativação por
  retry ou descarte) e tem regra própria fora do escopo deste PRD.
- Mudança no contrato de eventos (`AssinaturaSolicitada` e derivados). A
  correção é de guarda, não de payload. Nenhum evento deixa de ser publicado
  pelo caminho rejeitado; pelo contrário, deixa de ser emitido indevidamente.
- Reescrita da máquina de estados do agregado. As transições existentes para
  `EM_RENOVACAO` estão corretas; apenas a unicidade precisa cobri-las.

## Constraints

- A alteração do índice ocorre por uma nova migration Flyway (`V12`), sem
  editar migrations já aplicadas. O projeto nunca reescreve `V3`.
- A execução acontece em worktree isolada, coordenando a numeração `V12` com
  worktrees concorrentes que também possam tocar migrations do Assinatura
  Service.
- Os testes de integração de banco rodam contra Postgres na porta 5433 e
  compartilham a base sem cleanup entre métodos. Qualquer novo teste de
  unicidade envolvendo `EM_RENOVACAO` precisa rodar via `make test-integration`
  (que recria o schema) e não pode assumir base limpa entre execuções.

## Acceptance Criteria

### Solicitação durante renovação em andamento

- Given um usuário com assinatura `EM_RENOVACAO`, when ele solicita uma nova
  assinatura via `POST /assinaturas`, then a API responde `409 Conflict` com o
  mesmo corpo de erro já usado para `ATIVA` e `AGUARDANDO_PAGAMENTO`.
- Given o mesmo cenário, then nenhum evento `AssinaturaSolicitada` é gravado
  na outbox nem publicado.

### Concorrência entre checagem e insert

- Given dois processos tentando criar assinatura para o mesmo usuário onde um
  deles já gravou uma assinatura `EM_RENOVACAO`, when o segundo tenta o
  insert, then o índice único parcial rejeita a operação e o comando converte
  para o mesmo `409`, sem vazar `DataIntegrityViolationException`.

### Recuperação da renovação não bloqueia o usuário

- Given uma assinatura `EM_RENOVACAO` que teve o pagamento aprovado, when o
  ciclo rola e ela volta a `ATIVA`, then o usuário permanece bloqueado para
  novas adesões exatamente como antes, pois `ATIVA` continua coberto. Não há
  regressão de bloqueio.

### Cancelamento durante renovação

- Given uma assinatura `EM_RENOVACAO`, when o usuário solicita cancelamento,
  then o fluxo de cancelamento existente (que já trata `EM_RENOVACAO`)
  permanece funcionando, agendando o fim de ciclo. A unicidade não interfere
  no cancelamento.

### Manutenção da invariante

- Given o conjunto de status considerados "abertos" pela verificação de
  negócio, then ele contém exatamente `AGUARDANDO_PAGAMENTO`, `ATIVA` e
  `EM_RENOVACAO`, e um teste pinneia esse conjunto para detectar regressão
  futura.
