# PRD: Observabilidade de falhas da outbox

**Data:** 2026-08-03
**Status:** Rascunho
**Escopo:** BE-25 do roadmap consolidado. Desdobra o Must Have "Observabilidade de
falhas" em um documento focado, executável em worktree própria.

## Problema

Eventos que esgotam as retentativas na outbox caem no status `FALHA` e ficam
presos no banco. Hoje a única forma de saber que há acúmulo de falhas é ler log
ou abrir SQL na tabela `outbox`. Nenhuma dessas opções escala para monitoramento:
falhas definitivas acumulam silenciosamente até alguém perceber, e evento de
negócio importante (ativação, renovação, suspensão, cancelamento) pode se perder
sem alerta.

Quem sente a dor é o **operador**, que não consegue detectar acúmulo de falhas a
tempo de agir. O BE-24 entregou a recuperação automática (`FALHA` →
`RETENTATIVA_DLQ` após quarentena, com teto de ciclos), mas sem métrica o
operador não sabe se a recuperação está dando conta nem quando o teto foi
atingido e o evento virou terminal.

## Contexto

O sistema já tem o pipeline de tracing completo (PR #13): OpenTelemetry, Jaeger
via docker-compose, propagação de `traceparent` em HTTP e Kafka, `traceId` nos
logs. O pipeline de métricas está intencionalmente incompleto: o Micrometer está
no classpath (via `spring-boot-starter-opentelemetry`), mas o export OTLP de
métricas foi desabilitado por autoconfig exclude nos dois serviços, porque o
serviço exporta apenas traces localmente. O endpoint do Actuator expõe só
`health`. Não há Prometheus, não há `/actuator/metrics`.

A única métrica customizada existente é um contador de tentativas de retry do
gateway (`pagamento.gateway.retry.tentativas`, BE-21), no Pagamento. O Assinatura
não tem nenhuma métrica customizada. Não existe query de contagem por status na
`OutboxRepository` de nenhum dos dois serviços.

A tabela `outbox` já tem índice parcial em `FALHA` (`idx_outbox_recuperacao_dlq`,
migration V13/V11), criado pelo BE-24. Uma métrica de contagem por status
beneficia desse índice sem migration nova.

O volume de mensagens retido nas DLQs de consumer (`*-dlq` no Kafka) é **outro
fluxo**, disjunto da outbox. Fica fora deste PRD: depende do BE-27 (listener de
replay, ainda não entregue) e do BE-28 (config operacional dos DLTs) para fazer
sentido. Este PRD fecha o Must Have "número de eventos em `FALHA` por serviço" do
PRD guarda-chuva.

## Requisitos

### Must Have

- **Exposição de métricas via Prometheus.** O endpoint `/actuator/prometheus` é
  habilitado nos dois serviços, expondo as métricas do Micrometer (JVM, Kafka,
  customizadas) no formato que o Prometheus coleta. A dependência
  `micrometer-registry-prometheus` é adicionada a cada serviço.
- **Gauge de eventos em `FALHA` por serviço.** Uma métrica expõe o número de
  eventos em `FALHA` na outbox, por serviço, atualizada periodicamente. É a
  métrica que torna a falha definitiva visível sem ler log.
- **Presença nos dois serviços.** A métrica deve existir no Assinatura e no
  Pagamento, com o mesmo nome e tags, para que o operador cruze os dois num
  dashboard único.

### Should Have

- **Gauge por status da outbox.** Além de `FALHA`, expor contagens para
  `PENDENTE` e `RETENTATIVA_DLQ`, para que o operador veja o estado do ciclo de
  publicação e recuperação como um todo, não só o terminal.

### Out of Scope

- **Volume das DLQs de consumer (`*-dlq` no Kafka).** Fluxo disjunto, depende do
  BE-27 (não entregue) e do BE-28. Fica para depois.
- **Alerting externo** (PagerDuty, Slack, alertas do Prometheus). Escopo de
  plataforma. A métrica deve existir para que o alerta seja montado por cima.
- **Alinhamento de logging da outbox** (DEBUG vs WARN, `attempt` vs `tentativa`).
  É o BE-28. Este PRD não toca nos logs.
- **Dashboard pronto.** A métrica é entregue; a montagem do dashboard no Grafana
  é tarefa de operação.
- **Migrations.** A métrica lê a coluna `status` existente; o índice parcial de
  `FALHA` já vem do BE-24. Não há coluna nem tabela nova.

## Restrições

- **Sem quebra de contrato Kafka ou de API.** A exposição de métricas é um
  endpoint novo do Actuator, não afeta rotas de negócio nem tópicos.
- **Configuração externalizada.** A exposição do endpoint segue o padrão
  `management.endpoints.web.exposure.include` já adotado. Nada de segredo nem
  credencial novo.
- **Padrão de nomenclatura de métricas.** Seguir a convenção do Micrometer:
  nome em notação de ponto (`assinatura.outbox.eventos`, por exemplo), tags em
  lowercase, snake_case para valores de tag (`status=falha`). Alinhar o nome
  entre os dois serviços.
- **Sem payload nem PII na métrica.** A métrica carrega apenas contagem e tags
  de status, nunca conteúdo de evento.
- **Convenções do monorepo.** Commits PT-BR, Conventional Commits, Google Java
  Style, Javadoc obrigatório em todo símbolo público novo.

## Critérios de Aceitação

### Exposição Prometheus

- Dado o serviço rodando, quando o operador consulta `GET /actuator/prometheus`,
  então o endpoint responde no formato text/plain do Prometheus, contendo as
  métricas da JVM, do Kafka e as customizadas.
- Dado o endpoint exposto, quando o Prometheus scrapeia, então ele consegue
  coletar as métricas sem erro de formato.
- Dado o serviço rodando, quando o operador consulta `GET /actuator/health`,
  então o comportamento do health permanece inalterado.

### Gauge de eventos em FALHA

- Dado N eventos em `FALHA` na outbox de um serviço, quando o operador consulta a
  métrica, então o gauge expõe o valor N, por serviço.
- Dado um evento que cai em `FALHA`, quando ele transita de status, então o gauge
  reflete a nova contagem na próxima atualização.
- Dado o gauge configurado com atualização periódica, quando o serviço sobe, então
  ele passa a publicar o valor corrente sem intervenção manual.

### Presença nos dois serviços

- Dado a métrica implementada nos dois serviços, quando o operador consulta o
  Assinatura e o Pagamento, então ambos expõem a métrica com o mesmo nome e a
  mesma tag de status, distinguíveis por serviço (tag `servico` ou nome por
  serviço).
- Dado eventos em `FALHA` em qualquer um dos dois serviços, quando o operador
  consulta, então consegue identificar em qual serviço o acúmulo está.

### Gauge por status (Should Have)

- Dado a métrica implementada com tag de status, quando o operador consulta,
  então ele vê contagens separadas para `PENDENTE`, `RETENTATIVA_DLQ` e `FALHA`,
  permitindo distinguir o terminal do recuperável.
