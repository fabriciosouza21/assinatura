# Mecanismo de retries da renovação automática

**Versão alvo:** `0.3.0`
**Diagrama:** `renovacao-mecanismo-retries.puml` (este diretório)
**Decisão de referência:** D-R4 do plano técnico `docs/roadmap/2026-07-31-plano-implementacao-renovacao.md`

A renovação automática tem **três contadores de retentativa independentes**. Cada
um mede uma falha diferente, mora num lugar diferente, e tem um terminal diferente.
A regra central do design é que **esgotar um deles não dispara nenhum outro**: são
domínios de falha separados. Confundir os três é o erro clássico que esta separação
existe para evitar.

## Os três contadores

| Contador | Onde mora | O que retenta | Janela | Resultado ao esgotar |
|----------|-----------|---------------|--------|----------------------|
| `outbox.tentativas` | DB Assinatura (tabela `outbox`) | Publicar o evento no Kafka | segundos | status `FALHA` (DLQ persistida do produtor) |
| `consumer.tentativas` | Kafka (`DefaultErrorHandler`) | Processar o evento que chegou | segundos | Kafka DLT (tópico `*-dlq`) |
| `cobranca.tentativas` | DB Pagamento (`tentativa_cobranca`) | Cobrar a renovação no gateway | dias | suspensão da assinatura (resultado de negócio) |

O ponto que costuma confundir: uma falha técnica no consumer (Kafka DLT) **não
suspende a assinatura**. Uma recusa de pagamento (resultado de negócio) **não vai
para a DLT**. São falhas em lados opostos da mensageria, com consequências opostas.

## 1. Retentativa de publicação (outbox)

**Alvo:** colocar o evento *dentro* do Kafka.

O Assinatura Service grava o negócio (a renovação) e o evento na tabela `outbox`
na mesma transação. Um `OutboxPublisher` separado (`@Scheduled`) drena a outbox:
`SELECT ... FOR UPDATE SKIP LOCKED` onde `status = PENDENTE`, publica no Kafka e
marca `PUBLICADO`. Em falha, incrementa `tentativas` e reagenda com backoff + jitter.
Após 3, marca `FALHA`.

O que o outbox **não** retenta: consumo, gateway, decisão de negócio. A garantia
do outbox para no Kafka — entrega pelo menos uma vez ao broker, não ao consumidor.
Uma vez `PUBLICADO`, o trabalho do outbox acabou.

A fronteira é proposital. Se o outbox tivesse que esperar o consumidor confirmar,
ele voltaria a ser um dual-write distribuído entre Assinatura, Kafka e Pagamento,
que é exatamente o problema que o padrão existe para evitar. A separação limpa é:
o produtor garante a chegada à fila, o consumidor garante o processamento, o Kafka
no meio só transporta.

### Caso de canto: republicação

Se a publicação no Kafka funcionar, mas o `UPDATE outbox SET status = PUBLICADO`
falhar logo depois, o evento está no Kafka mas a linha segue `PENDENTE`. No próximo
tick, o publisher republica o mesmo evento. Isso é esperado, não é bug: é a fonte
da entrega *at-least-once*. O consumidor deduplica por `eventId` e a republicação é
absorvida sem efeito. O outbox prefere republicar a arriscar perder um evento.

## 2. Retentativa de consumo (Kafka)

**Alvo:** processar o evento que *chegou* ao Kafka.

O `DefaultErrorHandler` (do Spring Kafka, herdado do 0.2.0) retenta até 3 vezes com
backoff exponencial + jitter. Trata falha técnica de consumo: payload inválido,
erro de banco, bug inesperado. `EventoInvalidoException` é não-retentável e vai
direto para a DLT sem consumir as 3 tentativas.

A DLT (`renovacao-solicitada-dlq`, `renovacao-resultado-dlq`) é a fila morta do
**consumidor**. Significa: o evento chegou ao Kafka, mas quem consome não conseguiu
processar. Diferente da `FALHA` do outbox (que mora no banco), a DLT mora no Kafka.

## 3. Retentativa de cobrança (negócio)

**Alvo:** cobrar a renovação no gateway *após uma decisão de recusa*.

Esta é a que mais se confunde com "retry" genérico, mas tem natureza totalmente
diferente das duas anteriores. Uma recusa de pagamento (saldo insuficiente, cartão
expirado, fraude) não é uma falha transitória: não muda em 100ms. A próxima
tentativa faz sentido em D+1, quando o cliente talvez tenha regularizado a situação.

Por isso o contador mora na tabela `tentativa_cobranca` (campo `numero`, 1 a 3) e a
janela é de dias, não de segundos. O scheduler do BE-12 busca tentativas vencidas
(`proxima_tentativa_em <= now()`), chama o gateway, e o webhook do BE-13 decide o
resultado. Apenas três recusas **definitivas** (decisão de negócio) produzem
`RenovacaoTentativasEsgotadas` e suspendem a assinatura.

### Retry técnico vs. retry de negócio

Dentro do BE-12 há **dois** retries, e é crucial não misturá-los:

| | Retry técnico (WebClient) | Retry de negócio (scheduler) |
|---|---|---|
| O que falhou | Timeout, 5xx do gateway | Recusa (saldo, cartão, fraude) |
| Natureza | Transitória, muda em segundos | Decisão de negócio, persistente |
| Janela | Segundos | Dias (D+1, D+3, D+7) |
| Idempotency-Key | Mesma chave | Nova chave por tentativa |
| Incrementa `numero`? | Não | Sim |
| Quem decide o terminal | Próprio WebClient | Webhook (BE-13) |

O WebClient retry reenvia a mesma cobrança com a mesma `Idempotency-Key =
renovacaoId:tentativa`, evitando o pior bug de pagamento: cobrar duas vezes a
mesma tentativa por causa de uma falha de rede. O gateway deduplica pela chave,
então mesmo que o retry chegue após a cobrança já criada, devolve o mesmo
`paymentId` em vez de criar uma segunda.

O retry de negócio cria uma tentativa nova (`numero = 2`, depois `3`), com nova
`Idempotency-Key`, agendada para o futuro. Não pode ser WebClient porque a decisão
vem assíncrona, via webhook, possivelmente horas depois, e o processo que fez o
`POST /v1/payments` já terminou. Sem a `tentativa_cobranca` persistida, não haveria
onde ancorar a decisão do webhook nem como contar as três recusas.

## Por que o Pagamento não tem outbox

O Pagamento Service publica seus eventos (`PagamentoRenovacaoAprovado`,
`RenovacaoTentativasEsgotadas`) via **publish-then-ACK**: `kafkaTemplate.send(...).get()`
antes de persistir o `eventId`. É o padrão oposto ao do Assinatura, justificado por
uma diferença de risco:

- No Assinatura, um evento fantasma (publicado de uma transação que voltou) cria
  cobrança indevida. O outbox é a proteção necessária.
- No Pagamento, uma publicação faltante se autocura pelo retry do webhook do
  gateway. Não há agregado de negócio a proteger na mesma transação da publicação.

O outbox é caro (tabela extra, scheduler, retry), então só entra onde o dual-write
realmente fere. Essa assimetria é deliberada e reflete o custo/benefício por serviço.

## Terminais resumidos

```
outbox.tentativas  = 3  →  outbox.status = FALHA     (DLQ do produtor, no banco)
consumer.tentativas = 3 →  Kafka DLT                  (topico-dlq, no Kafka)
cobranca.tentativas = 3 →  RenovacaoTentativasEsgotadas → Assinatura SUSPENSA
```

Nenhum dos três dispara outro. A suspensão da assinatura só acontece por três
recusas de negócio, jamais por falha técnica de publicação ou de consumo.
