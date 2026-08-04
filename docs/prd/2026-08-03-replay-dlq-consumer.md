# PRD: Replay da DLQ de consumer

**Data:** 2026-08-03
**Status:** Rascunho
**Escopo:** BE-27 do roadmap consolidado.

## Problema

Quando um `@KafkaListener` esgota as retentativas do `DefaultErrorHandler`, a
mensagem vai para um tópico `*-dlq` e fica retida no Kafka. Hoje nenhum serviço
consome esses tópicos, e a mensagem fica parada indefinidamente até intervenção
manual no broker.

Quem sente a dor é o **operador**, que precisa de replay para devolver uma
mensagem ao fluxo original após corrigir a causa da falha, sem mexer direto no
broker.

## Contexto

O sistema já tem retry robusto no consumo. `FixedBackOff(1000ms, 2)` no
assinatura e `ExponentialBackOff` com jitter no pagamento, ambos 3 tentativas.
Exceções não retentáveis (`EventoInvalidoException`) vão direto para a DLQ sem
consumir tentativas. Os consumers já deduplicam: 4 por `eventId` (tabelas
`*_evento_processado`) e 2 por chave natural (`assinaturaId`, `renovacaoId`).
Reprocessar é sempre seguro.

A DLQ de consumer é disjunta da DLQ da outbox. A outbox (`FALHA`, BE-24) recupera
falhas de **publicação**: o evento não chegou ao Kafka. A DLQ de consumer
recupera falhas de **consumo**: o evento chegou ao Kafka, mas o listener não
conseguiu processar. Forçar a DLQ de consumer pelo mecanismo de outbox reusaria a
engrenagem errada, porque o retry curto da outbox (segundos) e o teto que
suspende a assinatura foram desenhados para outro problema. A distinção está
documentada em `docs/renovacao/renovacao-mecanismo-retries.md`.

A solução mais simples que resolve o replay sem distorcer o outbox é um
`@KafkaListener` próprio para os tópicos `*-dlq`, desligado por padrão, que
republica a mensagem no tópico original. O operador liga por configuração e
reinicia o serviço quando quer drenar a DLQ.

## Requisitos

### Must Have

- **Listener de replay desligado por padrão.** Um `@KafkaListener` consome os
  tópicos `*-dlq` de cada serviço com `autoStartup` controlado por configuração
  (`APP_KAFKA_REPLAY_AUTO_STARTUP=false`). Desligado, nenhum consumer se conecta
  aos tópicos `-dlq` e o comportamento é idêntico ao atual.
- **República para o tópico original.** O replay reenvia a payload bruta ao
  tópico de origem, lido do header `kafka_dlt-original-topic` (anexado pelo
  `DeadLetterPublishingRecoverer`), preservando a chave. O listener original a
  consome e aplica os guards de idempotência já existentes.
- **Idempotência preservada.** O replay não duplica efeito de negócio. Os guards
  existentes por `eventId` e por chave natural continuam válidos, inclusive sob
  re-consumo concorrente.
- **Presença nos dois serviços.** O mecanismo deve existir no Assinatura e no
  Pagamento, cada um consumindo os tópicos `*-dlq` que produz.

### Should Have

- **BackOff longo no listener de replay.** O error handler do listener de replay
  usa um `BackOff` com intervalo longo configurável (default 1h), distinto do
  retry curto dos consumers normais. Assim, uma mensagem que volta a falhar ao
  ser republicada não gera um ciclo rápido de retry, e o operador tem tempo de
  agir entre as tentativas.

### Out of Scope

- **Endpoint REST de replay.** Não há padrão de auth admin no repo. O listener
  por configuração atende o caso operacional sem nova superfície de segurança.
- **Replay seletivo por `eventId`.** O listener republica tudo o que chega à DLQ
  enquanto ativo. Seletividade fica ao encargo do operador via ferramenta de
  broker antes de ligar.
- **Configuração operacional dos tópicos DLT** (partitions, RF, retenção). É o
  BE-28, que depende deste.
- **DLQ da outbox.** É o BE-24, já entregue. Fluxo disjunto.

## Restrições

- **Sem quebra de contrato Kafka.** Tópicos, chaves e payloads existentes não
  mudam. O replay só produz no tópico original, com a mesma payload e chave.
- **Configuração externalizada.** Habilitação do listener e parâmetros do BackOff
  seguem o padrão `APP_*`.
- **Sem migrations de banco.** O replay não adiciona estado novo; reusa os guards
  de idempotência existentes.
- **Padrão de logs estruturados.** SLF4J Fluent, `event` em `snake_case`, sem
  payload nem PII, em toda nova decisão. Toda cadeia termina em `.log()`.
- **Convenções do monorepo.** Commits PT-BR, Conventional Commits, Google Java
  Style, Javadoc obrigatório em todo símbolo público novo.

## Critérios de Aceitação

### Listener desligado por padrão

- Dado o listener desativado (`APP_KAFKA_REPLAY_AUTO_STARTUP=false`, default),
  quando o serviço sobe, então nenhum consumer se conecta aos tópicos `*-dlq` e o
  comportamento é idêntico ao atual.
- Dado o listener ativado via variável de ambiente, quando o serviço sobe, então
  ele passa a consumir os tópicos `*-dlq` e a republicar no tópico original.

### República para o tópico original

- Dado uma mensagem em `<topico>-dlq`, quando o replay a consome, então ela é
  produzida em `<topico>` (o tópico original lido do header
  `kafka_dlt-original-topic`), com a mesma chave e a mesma payload bruta.
- Dado o header `kafka_dlt-original-topic` ausente ou ilegível, quando o replay
  avalia o destino, então ele loga em WARN e descarta a mensagem, sem derrubar o
  listener.

### Idempotência preservada

- Dado um consumer com dedup por `eventId`, quando a mesma mensagem é
  republicada múltiplas vezes, então só a primeira produz efeito e as demais são
  absorvidas pelo guard.
- Dado um consumer com dedup por chave natural, quando o replay devolve uma
  mensagem já processada, então o guard de negócio absorve a duplicata sem criar
  cobrança nem tentativa nova.
- Dado o replay rodando em múltiplas instâncias, quando ambas consomem a mesma
  mensagem da DLQ, então o efeito de negócio não se duplica.

### BackOff longo

- Dado o listener de replay ativado, quando uma mensagem republicada volta a
  falhar no consumer original e retorna à DLQ, então o listener de replay só a
  reconsome após o intervalo longo configurado, sem gerar ciclo rápido.

### Presença nos dois serviços

- Dado o mecanismo presente nos dois serviços, quando o Pagamento tem uma mensagem
  em `assinatura-solicitada-dlq`, então ela é republicada em `assinatura-solicitada`
  e consumida pelo `AssinaturaSolicitadaConsumer`.
- Dado o mecanismo presente nos dois serviços, quando o Assinatura tem uma mensagem
  em `renovacao-resultado-dlq`, então ela é republicada em `renovacao-resultado` e
  consumida pelo `RenovacaoResultadoConsumer`.
