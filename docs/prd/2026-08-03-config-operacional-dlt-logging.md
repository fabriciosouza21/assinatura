# PRD: Configuração operacional dos DLTs e logging da outbox

**Data:** 2026-08-03
**Status:** Rascunho
**Escopo:** BE-28 do roadmap consolidado. Desdobra o Should Have "Configuração
operacional dos tópicos DLT" e "Consistência de logging" em um documento focado,
executável em worktree própria.

## Problema

Há dois defeitos operacionais que atrapalham quem monitora o sistema e quem
investiga falhas, ambos decorrentes da diferença de maturidade entre os dois
serviços ao declarar tópicos e ao logar.

**1. Tópicos DLQ sem config explícita.** Os 7 tópicos `*-dlq` (2 no assinatura, 5
no pagamento) são declarados de formas diferentes e ambas insuficientes para
operação controlada:

- O assinatura força `new NewTopic(nome, 1, (short) 1)` — 1 partição, fator de
  replicação 1. Funciona em dev, mas em produção não tem tolerância a falha e
  serializa o consumo.
- O pagamento usa `TopicBuilder.name(...).build()` sem especificar nada — cai nos
  defaults do broker (`num.partitions`, `default.replication.factor`), que variam
  entre ambientes e são imprevisíveis.
- Nenhum dos dois define retenção. Mensagens na DLQ podem expirar e sumir sem
  ninguém ver, ou se acumular para sempre.

Quem sente a dor é o **operador**, que não consegue prever o comportamento dos
tópicos de DLQ ao subir o sistema num broker novo, nem garantir que mensagens
problemáticas fiquem retidas pelo tempo necessário à investigação.

**2. Logging divergente na outbox.** A mesma falha de publicação é logada de
formas diferentes nos dois serviços, dificultando quem cruza logs numa
investigação:

- Assinatura loga falha temporária em **DEBUG** (invisível em produção, que roda
  em INFO) e sucesso em DEBUG.
- Pagamento loga falha temporária em WARN e sucesso em INFO.
- As chaves estruturadas divergem: `attempt` vs `tentativa`, `assinaturaId` vs
  `aggregateId`, `topic` vs `topico`. Pagamento registra `proximaTentativaEm`;
  assinatura não. Pagamento não anexa a causa no erro terminal; assinatura anexa.

Quem sente a dor é o **operador** que, ao investigar uma falha, precisa saber de
antemão qual serviço usa qual convenção para montar a consulta, e pode deixar
passar uma falha temporária que só aparece num dos dois serviços.

## Contexto

O BE-27 (PR #37) landou o replay da DLQ de consumer: um `@KafkaListener` dedicado
consome os `*-dlq`, desligado por padrão, e republica no tópico original. Esse
listener confirma a importância dos tópicos DLQ estarem bem configurados: agora
eles têm um consumidor (quando ligado), então partitions e retenção afetam o
comportamento do replay, não apenas o isolamento.

O BE-24 (PR #35) já entregou a recuperação da DLQ da **outbox** (`FALHA` →
`RETENTATIVA_DLQ`). A logging que este PRD alinha é justamente a do publisher que
transita esses estados. Hoje o `OutboxPublisher` é o ponto onde a divergência
vive.

Não há precedência no codebase para externalizar partitions/RF/retenção: nenhum
`NewTopic` define esses valores via propriedade, e não existem chaves
`APP_KAFKA_*_PARTICOES` ou equivalentes. O padrão estabelecido por este PRD é o
de **constantes no bean**, determinístico e sem knob de deploy.

A convenção de logging do `AGENTS.md` coloca "retries" em DEBUG, mas o
`docs/guidelines/logging.md` §8 estabelece o canonical para produtores Kafka:
INFO em sucesso, WARN em falha. A falha temporária da outbox é uma degradação
recuperável observável, então WARN é a classificação correta. Este PRD alinha os
dois serviços ao canonical WARN/INFO e atualiza a nota do `AGENTS.md` para deixar
claro que falha temporária da outbox é WARN, não DEBUG.

## Requisitos

### Must Have

- **Config explícita nos DLTs.** Os beans `NewTopic` dos 7 tópicos `*-dlq` (2 no
  assinatura, 5 no pagamento) definem partitions, fator de replicação e retenção
  explicitamente, como constantes no bean, em vez de herdarem defaults do broker
  ou forçarem 1/1.
- **Mesma config entre os serviços.** Os tópicos DLQ que ambos declaram (ex.:
  `renovacao-resultado-dlq`) usam a mesma configuração nos dois lados.
- **Logging de outbox alinhado em WARN/INFO.** A falha temporária de publicação é
  logada em WARN nos dois serviços (não DEBUG). O sucesso é logado em INFO nos
  dois. As chaves estruturadas passam a ser as mesmas, em snake_case.

### Should Have

- **Chaves unificadas e causa anexada.** As chaves estruturadas da outbox
  (`eventId`, identificador do agregado, tópico, tentativas, próxima tentativa,
  código de razão) passam a ter o mesmo nome nos dois serviços. O erro terminal
  anexa a causa (`setCause`) nos dois, no ponto de falha definitiva.
- **Nota de convenção no AGENTS.md.** Atualizar a seção de logging para deixar
  explícito que a falha temporária da outbox é WARN (degradação recuperável), não
  DEBUG, resolvendo a ambiguidade com a regra geral de "retries em DEBUG".

### Out of Scope

- **Externalização via `APP_*`.** Partitions, RF e retenção ficam como constantes
  no bean, determinísticas. Não viram variáveis de ambiente.
- **Tópicos não-DLQ.** Apenas os `*-dlq` recebem config explícita. Os tópicos de
  negócio (`assinatura-solicitada`, etc.) mantêm a configuração atual.
- **Replay da DLQ de consumer.** É o BE-27, já entregue. Este PRD só ajusta a
  configuração dos tópicos que ele consome quando ligado.
- **Recuperação da outbox.** É o BE-24, já entregue. Este PRD só alinha o
  logging do publisher que transita os estados.
- **DLQ da outbox vs DLQ de consumer.** Ajustes de métrica de volume ficam no
  BE-25.

## Restrições

- **Sem quebra de contrato Kafka.** Nomes de tópicos, chaves e payloads não
  mudam. Apenas partitions, RF e retenção dos DLQs e o formato dos logs.
- **Sem migrations de banco.** Nada deste PRD toca a tabela `outbox` ou qualquer
  tabela. É config de tópico + logging.
- **Configuração determinística.** As constantes de partitions/RF/retenção são
  fixas no código, não dependem de variável de ambiente nem de default de broker.
- **Padrão de logs estruturados.** SLF4J Fluent, `event` em `snake_case`, sem
  payload nem PII, em toda decisão. Toda cadeia termina em `.log()`. `setCause`
  só no ponto de falha definitiva.
- **Convenções do monorepo.** Commits PT-BR, Conventional Commits, Google Java
  Style, Javadoc obrigatório em todo símbolo público novo.

## Critérios de Aceitação

### Configuração explícita dos DLTs

- Dado os beans `NewTopic` de DLQ, quando sobem no broker, então partitions, fator
  de replicação e retenção estão definidos explicitamente como constantes no bean,
  nos dois serviços, sem depender de defaults do broker.
- Dado um tópico DLQ declarado em ambos os serviços (`renovacao-resultado-dlq`),
  quando sobe em qualquer lado, então a configuração de partitions, RF e retenção
  é a mesma.
- Dado a retenção configurada, quando mensagens acumulam na DLQ, então elas
  permanecem pelo tempo definido, sem expiração implícita do broker.

### Logging de outbox alinhado

- Dado uma falha temporária de publicação na outbox, quando ela ocorre em qualquer
  dos dois serviços, então é logada em WARN com as mesmas chaves estruturadas em
  snake_case.
- Dado uma publicação com sucesso, quando ela ocorre em qualquer dos dois
  serviços, então é logada em INFO (não DEBUG) com as mesmas chaves.
- Dado uma falha terminal de publicação, quando ela ocorre em qualquer dos dois
  serviços, então é logada em ERROR com `setCause` anexado e as mesmas chaves.

### Chaves unificadas

- Dado a falha temporária logada, quando um operador cruza os logs dos dois
  serviços, então encontra a tentativa, o identificador do agregado e o tópico
  sob os mesmos nomes de chave em ambos, sem precisar saber qual serviço usa qual
  convenção.
- Dado o erro terminal, quando ele ocorre, então as chaves de razão e tentativas
  têm o mesmo nome nos dois serviços.

### Nota de convenção

- Dado a seção de logging do `AGENTS.md`, quando um desenvolvedor a lê, então
  encontra a regra explícita de que a falha temporária da outbox é WARN
  (degradação recuperável), não DEBUG, sem ambiguidade com a regra geral de
  retries.
