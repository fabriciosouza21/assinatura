# PRD: Cancelamento de assinatura

**Date:** 2026-08-01
**Status:** Draft

## Problem

O cliente que quer deixar de pagar uma assinatura hoje não tem caminho para
isso. A assinatura é renovada automaticamente todo mês, e o único "opt-out"
existente vive dentro do scheduler de renovação: quem nunca marcou
`renovacaoAutomatica = false` não tem como parar as cobranças sem abandonar o
produto. O cancelamento é o mecanismo mais básico de retenção: sem ele, o
cliente não controla o próprio gasto, e a confiança no produto cai.

## Background

O contrato do cancelamento já foi desenhado e documentado
(`docs/openapi/cancelamento-assinatura.yaml` e
`docs/cancelamento/cancelamento-assinatura.puml`), e o ADR 0002 define onde a
capacidade `cancelamento/` entra na organização de pacotes dos dois serviços.
Parte do código já existe: o agregado `Assinatura` tem o método `cancelar()`
(transição `ATIVA` para `CANCELADA`) e o scheduler de vencimentos já efetiva
o opt-out quando `renovacaoAutomatica = false`. O que falta é a porta de
entrada para o cliente: o endpoint, o comando de domínio, os eventos de
integração e o comportamento do Pagamento Service.

A semântica escolhida é a de opt-out, não a de interrupção: o cliente cancela
a renovação automática e mantém o acesso até o fim do ciclo já pago. Ele não
perde o que contratou, e o negócio não abre mão da receita do ciclo corrente.

## Requirements

### Must Have

- O cliente autenticado (dono da assinatura) consegue cancelar uma assinatura
  pelo endpoint `POST /assinaturas/{uuid}/cancelamento`.
- Assinatura `ATIVA`: o cancelamento é agendado. O status permanece `ATIVA`, a
  renovação automática é desligada e o acesso segue até o fim do ciclo pago.
- Assinatura `EM_RENOVACAO`: o cancelamento é agendado sem abortar a cobrança
  em curso. Se aprovada, o ciclo recém-pago é o último; se as tentativas se
  esgotarem, a assinatura é suspensa como já ocorre hoje.
- Assinatura sem ciclo pago (`AGUARDANDO_PAGAMENTO`, `PAGAMENTO_RECUSADO`,
  `SUSPENSA`): o cancelamento é imediato e o status vai para `CANCELADA`.
- Assinatura já cancelada: a operação é idempotente, não gera evento novo e
  responde 200 com o estado atual.
- A resposta informa `id`, `status`, `renovacaoAutomatica` e `acessoAte`
  (fim do ciclo pago, nulo quando não há ciclo a preservar).
- Após o cancelamento agendado, o scheduler de vencimentos efetiva a
  transição para `CANCELADA` no fim do ciclo, sem criar nova renovação nem
  disparar cobrança.
- O Pagamento Service para de tentar cobrar a renovação da assinatura
  cancelada: tentativas pendentes são canceladas e o scheduler não cria novas.
- Erros claros: 404 para uuid desconhecido, 403 para token de outro usuário,
  401 sem token válido, 400 para uuid malformado.

### Should Have

- Os eventos de cancelamento (`CancelamentoAgendado` e `AssinaturaCancelada`)
  trafegam pela outbox na mesma transação da alteração, com deduplicação por
  `eventId` no consumidor, seguindo o padrão dos eventos existentes.

### Out of Scope

- Reembolso ou crédito proporcional: o ciclo pago não é devolvido.
- Cancelamento imediato com perda de acesso: o acesso segue até o fim do ciclo.
- Cancelamento por administrador ou por outros usuários.
- Notificação por e-mail ao cliente sobre o cancelamento.
- Mudança de plano, pausa temporária ou outras operações de ciclo de vida.

## Constraints

- O endpoint exige JWT do dono da assinatura (diferente de `POST /assinaturas`,
  que é público até o login de cliente, conforme ADR 0001).
- O cancelamento e a varredura de vencimentos precisam decidir o ciclo sob
  bloqueio de linha, para nunca divergirem sobre o mesmo ciclo.
- A versão alvo é `0.3.0` em ambos os serviços.

## Acceptance Criteria

### Cancelamento agendado (assinatura ativa)

- Given uma assinatura `ATIVA` com ciclo pago em andamento, when o dono chama
  `POST /assinaturas/{uuid}/cancelamento`, then a resposta é 200 com
  `status: ATIVA`, `renovacaoAutomatica: false` e `acessoAte` igual ao fim do
  ciclo, e nenhuma renovação futura é criada.
- Given o cancelamento agendado, when o fim do ciclo chega, then a varredura de
  vencimentos marca a assinatura como `CANCELADA` e nenhuma cobrança é
  disparada.

### Cancelamento imediato (sem ciclo pago)

- Given uma assinatura `AGUARDANDO_PAGAMENTO`, `PAGAMENTO_RECUSADO` ou
  `SUSPENSA`, when o dono cancela, then o status vai para `CANCELADA` e
  `acessoAte` é nulo.

### Renovação em curso

- Given uma assinatura `EM_RENOVACAO`, when o dono cancela, then a cobrança em
  curso não é abortada e o resultado dela segue o fluxo normal.

### Idempotência

- Given uma assinatura já `CANCELADA`, when o dono chama o endpoint de novo,
  then a resposta é 200 sem alterações e sem eventos novos.

### Autorização

- Given um token válido de outro usuário, when ele tenta cancelar, then a
  resposta é 403 com corpo vazio.
- Given um token ausente, when a chamada é feita, then a resposta é 401.
- Given um uuid que não existe, when a chamada é feita, then a resposta é 404.

### Pagamento Service

- Given uma assinatura com tentativas de cobrança de renovação pendentes, when
  o evento de cancelamento é consumido, then as tentativas pendentes são
  canceladas e nenhuma cobrança nova é criada para os ciclos seguintes.
- Given o mesmo `eventId` entregue duas vezes, when o consumidor processa,
  then a segunda entrega não causa alteração.
