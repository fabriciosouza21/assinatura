# Roadmap: Renovação automática de assinatura

**Design de referência:** `docs/renovacao/renovacao-automatica-assinatura.puml` e `docs/renovacao/renovacao-automatica-retry-dlq.puml`
**Versão alvo:** `0.3.0`
**Branch base:** `develop`
**Plano técnico:** `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`

A assinatura hoje vale um mês e morre. Não há receita recorrente. A renovação
automática estende cada ciclo cobrando o cliente de novo, respeita quem optou
por não renovar e suspende o acesso após três recusas seguidas.

---

## Backend — Assinatura Service

### BE-7 — Publica eventos pelo tipo, não pelo tópico fixo
`feat/outbox-rota-por-evento`
- A outbox deixa de mandar todo evento num tópico só e passa a escolher o
  tópico pelo tipo do evento
- É o que permite publicar o pedido de renovação no lugar certo

### BE-8 — Modelo de ciclo e renovação na assinatura
`feat/dominio-ciclo-renovacao`
- A assinatura ganha início e fim de ciclo, data da próxima renovação e a
  opção de renovar ou não
- Cada renovação vira um registro próprio, um por ciclo, sem chance de cobrar
  duas vezes o mesmo mês

### BE-9 — Varredura de assinaturas vencidas
`feat/scheduler-renovacao`
- Um agendador encontra assinaturas no fim do ciclo e dispara a renovação
  automaticamente
- Quem optou por não renovar é cancelado ao vencer; quem renovou segue com
  acesso enquanto a nova cobrança é decidida

### BE-10 — Atualiza a assinatura pelo resultado da renovação
`feat/consome-resultado-renovacao`
- Cobrança aprovada estende o ciclo e mantém a assinatura ativa; três recusas
  suspendem o acesso
- Entregas repetidas do mesmo resultado não aplicam a mudança duas vezes

---

## Backend — Pagamento Service

### BE-11 — Cria a cobrança da renovação *(entregue — PR #10)*
`feat/pagamento-cria-cobranca-renovacao`
- O Pagamento Service consome o pedido de renovação e cria a primeira
  tentativa de cobrança no gateway
- Um pedido reenviado não cria cobrança duplicada

### BE-12 — Repete a cobrança após recusa
`feat/scheduler-tentativas-cobranca`
- Um agendador busca tentativas vencidas e cobra de novo no gateway
- Falha técnica do gateway não consome uma das três tentativas

### BE-13 — Webhook decide a renovação
`feat/webhook-renovacao`
- O webhook do gateway decide a cobrança da renovação: aprovada encerra,
  recusa agenda a próxima tentativa, três recusas esgotam
- Só confirma o gateway depois de publicar o resultado

---

## Documentação

### DOCS-2 — Changelog e versão
`release/0.3.0`
- Atualizar versão para `0.3.0` em ambos os `pom.xml`
- Atualizar `CHANGELOG.md` com a renovação automática

---

## Ordem dos Entregaveis

| # | Entregavel | Depende de | Status |
|---|-----------|-----------|--------|
| 0 | Travar contratos de evento de renovação | — | [x] |
| 1 | Publica eventos pelo tipo (BE-7) | 0 | [x] |
| 2 | Modelo de ciclo e renovação (BE-8) | 0 | [x] |
| 3 | Cria a cobrança da renovação (BE-11) | 0 | [x] (#10) |
| 4 | Varredura de assinaturas vencidas (BE-9) | 1, 2 | [x] |
| 5 | Atualiza assinatura pelo resultado (BE-10) | 2, 0 | [x] |
| 6 | Repete a cobrança após recusa (BE-12) | 3 | [x] |
| 7 | Webhook decide a renovação (BE-13) | 3 | [x] |
| 8 | Changelog + bump versão 0.3.0 (DOCS-2) | 1-7 | [ ] |

**Paralelismo:** os itens 1, 2 e 3 abrem juntos após travar os contratos.
Após as fundações, até quatro entregas rodam em paralelo entre os dois
serviços, sem se tocar no código do outro. O detalhamento das dependências e
contensões está no plano técnico.
