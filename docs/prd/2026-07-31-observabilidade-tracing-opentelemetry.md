# PRD: Observabilidade com tracing distribuído via OpenTelemetry

**Data:** 2026-07-31
**Status:** Draft
**Track:** Observabilidade / OBS-1
**Serviços:** Assinatura, Pagamento, Mock de Pagamento
**Versão alvo:** `0.2.0`

## Problema

Quando uma assinatura é solicitada, o fluxo atravessa dois microserviços, dois
hops de Kafka e um mock de gateway, fechando um ciclo de oito etapas antes da
assinatura ficar ativa. Hoje não existe como acompanhar esse caminho. Os logs de
console só contêm ruído de infraestrutura: dumps inteiros de `ProducerConfig` e
`ConsumerConfig` do Kafka, sem nenhuma linha que diga o que aconteceu de
negócio. O serviço de Pagamento não tem uma única chamada de log. O de
Assinatura tem exatamente uma, um `WARN` de falha de publicação.

Não há correlação entre serviços. Sem `traceId`, sem `correlationId`, sem MDC. A
única forma de ligar uma etapa à outra é inferir pela ordem dos timestamps e
pelo `assinaturaId`, que aparece em alguns eventos mas não nos logs. Quando
alguma coisa trava no meio, o desenvolvedor fica sem saber se o evento saiu do
Assinatura, se o Pagamento consumiu, se o mock respondeu ou se o webhook
chegou. A depuração vira adivinhação.

Quem sente a dor: o desenvolvedor depurando o fluxo em ambiente local e a
operação diante de qualquer incidente futuro. Hoje o custo é dev-time perdido
reproduzindo cenários para enxergar onde o fluxo parou. Conforme a release 0.2.0
fecha o fluxo de pagamento de ponta a ponta, a falta de rastreabilidade deixa
de ser um incômodo e vira bloqueio operacional.

## Background

O sistema roda com a configuração de logging padrão do Spring Boot 4.1: nenhum
dos quatro `application*.yaml` define `logging.*`, não existe `logback-spring.xml`
em qualquer serviço, e o pattern de console é o default. O mock de pagamento em
Go usa o pacote `log` da biblioteca padrão, com saída em stderr e sem
estrutura de nível.

O resultado é o que se vê nos logs hoje: o `OutboxPublisher` do Assinatura, ao
subir um producer, dispara a configuração completa do Kafka em nível INFO (mais
de cem linhas de `acks`, `bootstrap.servers`, `sasl.*`, `ssl.*`), e o consumer do
Pagamento faz o mesmo. Cada uma dessas linhas consome espaço sem informação de
negócio.

Não existe nenhum mecanismo de correlação transversal. Grep por `correlation`,
`traceId`, `MDC`, `sleuth` e `micrometer` nos fontes e nos `pom.xml` retorna
vazio. A correlação entre os dois serviços é puramente semântica de domínio: o
`assinaturaId` (UUID público da assinatura) atravessa o fluxo como
`externalReference` no gateway e aparece em ambos os eventos Kafka
(`AssinaturaSolicitada` e `PagamentoStatusAtualizado`), mas nunca chega aos logs.

O Spring Boot 4.1 introduziu o `spring-boot-starter-opentelemetry`, um único
starter que substitui o combo de três dependências que era necessário no Boot
3.x. Ele auto-instrumenta a propagação de contexto de trace via HTTP e Kafka,
injeta `traceId` e `spanId` no MDC e ajusta o pattern de log automaticamente,
sem código manual na maioria das fronteiras. O mock de pagamento, por ser Go
com `net/http` puro, precisa de instrumentação própria via `otelhttp`, mas o
volume de código é pequeno e bem delimitado.

Esta track entrega rastreabilidade completa do fluxo de subscrição de ponta a
ponta, sem mudar nenhum contrato de negócio. É um trabalho transversal: corta
os três componentes do repositório e toca apenas pontos de observabilidade, sem
alterar regras de domínio existentes.

## Requisitos

### Must Have

- O fluxo completo de uma assinatura, do `POST /assinaturas` até a atualização
  de status final, é visível como um único trace contínuo, atravessando
  Assinatura, Pagamento e o mock de gateway.
- O trace usa propagação W3C via header `traceparent`, injetado e extraído
  automaticamente nas fronteiras HTTP e Kafka.
- Cada linha de log de console dos serviços Spring carrega `traceId` e `spanId`
  no pattern, permitindo correlação manual por `grep` quando o Jaeger não estiver
  disponível.
- Um backend de visualização (Jaeger) sobe junto com o ambiente via
  `docker compose up` e recebe os traces via OTLP HTTP, sem configuração manual
  adicional.
- O nível de log das bibliotecas de infraestrutura (Kafka, Spring, Hibernate) é
  reduzido para `WARN` em ambos os serviços, silenciando o dump de configuração
  que domina o console hoje.
- O mock de pagamento propaga o contexto de trace da chamada do Pagamento
  Service para o envio do webhook, de forma que o webhook não apareça como um
  trace órfão no Jaeger.
- Cada uma das fronteiras do fluxo (entrada de HTTP, chegada e partida de Kafka,
  chamada ao gateway, recebimento de webhook, atualização de status) emite
  exatamente um log de negócio em nível INFO com o `assinaturaId` ou
  `paymentId` relevante.
- Os exception handlers existentes registram exceções não tratadas em nível
  ERROR, em vez de capturá-las silenciosamente.

### Should Have

- O endpoint OTLP do Jaeger e o sampling são externalizados por variável de
  ambiente, de forma que `0.2.0` rode com sampling de 100% em dev sem exigir
  mudança para calibrar depois.
- A porta do mock de pagamento e a chave HMAC já externalizadas continuam como
  estão; o endpoint do exporter OTLP do mock também é externalizado.

### Out of Scope

- Exportação de logs via OTLP (logback appender OTLP). Apenas traces são
  exportados. Logs continuam no console, correlacionados ao trace pelo
  `traceId` no pattern.
- Exportação de métricas via OTLP. O `micrometer-registry-otlp` vem no starter
  mas não será configurado nesta entrega.
- Spans manuais de domínio (`@Observed`, `@WithSpan` em comandos e serviços de
  domínio). A instrumentação automática cobre as fronteiras. Spans internos de
  negócio ficam para quando a leitura do Jaeger mostrar pontos cegos relevantes.
- Logs de depuração dentro da camada de domínio (dentro de `SolicitarAssinatura`,
  `CriarCobranca`, `ProcessarWebhookPagamento`, `ProcessarPagamento`). O escopo é
  as fronteiras do fluxo, não o interior de cada comando.
- Mudança nos contratos dos eventos Kafka ou do mock. Esta entrega não altera
  payload, header de negócio nem tópico.
- Coleta de traces em produção (sampling distinto, retention, backend
  persistente). O `all-in-one` do Jaeger com storage em memória atende ao caso de
  uso de desenvolvimento local.

## Constraints

- O `spring-boot-starter-opentelemetry` exige que clientes HTTP sejam
  construídos a partir de um `WebClient.Builder` injetado pelo Spring, e não
  instanciados diretamente com `WebClient.create()`. Sem isso, o trace não
  propaga da chamada ao gateway. Esta é a única restrição de código que afeta a
  instrumentação automática.
- A goroutine que despacha o webhook no mock (`dispatchWebhook`) hoje não recebe
  o contexto do request. Precisa receber `context.Context` para que o trace
  sobreviva à fronteira da thread. É o único ponto de cuidado no mock.
- Seguir as convenções do repositório: commits em Conventional Commits PT-BR,
  Javadoc obrigatório em tipos e métodos públicos Java, `make lint` e
  `make verify` passando nos dois serviços Spring, `go vet` e `go build`
  passando no mock.
- A versão dos serviços sobe para `0.2.0` (em sincronia com as tracks de fluxo
  em andamento que também travam essa versão).

## Acceptance Criteria

### Trace contínuo de ponta a ponta

- Given o ambiente de pé via `docker compose up`, when um cliente autenticado
  solicita uma assinatura e o fluxo completa, then o Jaeger em
  `http://localhost:16686` mostra um único trace encadeando spans dos três
  componentes: Assinatura, Pagamento e mock de gateway.
- Given uma assinatura solicitada e aprovada pelo mock, when o fluxo fecha,
  then o trace no Jaeger contém as oito etapas do fluxo sem buracos, incluindo
  os dois hops de Kafka e o webhook.

### Correlação via log

- Given qualquer log de negócio emitido durante o fluxo, when o desenvolvedor
  inspeciona o console, then a linha contém `[traceId,spanId]` no pattern e o
  mesmo `traceId` que aparece no trace do Jaeger.
- Given dois logs emitidos em serviços diferentes para a mesma assinatura, when
  comparados, then compartilham o mesmo `traceId`.

### Silêncio de infraestrutura

- Given o ambiente subindo, when o `OutboxPublisher` ou qualquer consumer Kafka
  instancia um producer/consumer, then o console não exibe mais o dump de
  `ProducerConfig`/`ConsumerConfig` em INFO (apenas em WARN ou superior).

### Fronteiras do fluxo logadas

- Given uma solicitação de assinatura recebida, when o controller processa,
  then um log INFO registra a entrada com `usuarioId` e plano.
- Given um evento `AssinaturaSolicitada` consumido pelo Pagamento, when o
  consumer processa, then um log INFO registra a chegada com `assinaturaId`.
- Given uma chamada ao gateway pelo `GatewayPagamentoClient`, when ela retorna,
  then um log INFO registra o `httpStatus` recebido junto ao `assinaturaId`.
- Given um webhook recebido, when o serviço valida e processa, then um log INFO
  registra o `eventId` e o `assinaturaId`.
- Given um evento `PagamentoStatusAtualizado` consumido pelo Assinatura, when o
  status final é aplicado, then um log INFO registra o `statusFinal` junto ao
  `assinaturaId`.

### Mock instrumentado

- Given o mock recebe uma chamada do Pagamento Service, when ele processa e
  dispara o webhook, then o span do mock e o span do envio do webhook aparecem
  no mesmo trace da chamada original, sem trace órfão.
- Given o mock cria um pagamento, when ele responde, then um log registra o
  `paymentId` gerado e o `externalReference`.

### Resiliência do exporter

- Given o Jaeger indisponível ou o endpoint OTLP inalcançável, when os
  serviços operam, then nenhum fluxo de negócio é bloqueado ou falha por causa
  da ausência do exporter.
