# Code Review: feature de criação de assinatura

**Branch:** `feature/correlacao-trace-assinatura-id`
**Escopo:** `SolicitarAssinatura`, `Assinatura`, `AssinaturaController`, `ProcessarPagamento`, evento outbox `AssinaturaSolicitada` (fluxo completo de criação até a confirmação do pagamento)
**Data:** 2026-08-01
**Método:** Revisão manual (leitura completa dos arquivos de domínio, controller, repositórios, migrations e testes do pacote `assinatura`)

## Resumo

O desenho geral do fluxo de criação é sólido: a regra "um usuário, uma assinatura aberta" é protegida por índice único parcial (`uq_assinatura_aberta_usuario`) com fallback em `catch (DataIntegrityViolationException)`; o insert da assinatura e o registro do evento na outbox são atômicos na mesma transação; o processamento do pagamento usa lock pessimista (`FOR UPDATE`) e uma tabela de idempotência dedicada. A cobertura de testes é boa, incluindo os casos de corrida na criação (índice único) e de eventos tardios/duplicados na confirmação de pagamento.

Três pontos precisam de atenção antes de fechar a feature.

## Veredito

**Necessita correções** — 1 Medium (race de idempotência) + 2 Low (validação de formato / timezone), nenhum bloqueante.

## Findings

### [Medium] Race entre o check de idempotência e o lock pessimista em `ProcessarPagamento`

**Arquivo:** `services/assinatura/src/main/java/com/globo/assinatura/assinatura/ProcessarPagamento.java:55-77`

`pagamentoEventoProcessadoRepository.existsByEventId(evento.eventId())` é checado **antes** de adquirir o lock pessimista (`buscarPorUuidParaAtualizacao`, `FOR UPDATE`) na assinatura. Em um cenário de rebalance do consumer Kafka (um consumer "zumbi" ainda processando enquanto a partição já foi reatribuída a outra instância), duas transações podem passar pelo check de idempotência antes que a primeira dê commit.

A segunda transação, após aguardar e adquirir o lock liberado pela primeira, chama `ativar()`/`recusarPagamento()` — que é idempotente pelo guard de status, então o domínio fica correto — mas em seguida `pagamentoEventoProcessadoRepository.save(...)` viola o índice único `uq_pagamento_evento_processado_event_id` (`V6__cria_tabela_pagamento_evento_processado.sql`) e lança `DataIntegrityViolationException` **não tratada**.

Essa exceção não está na lista de exceções não-retentáveis do `DefaultErrorHandler` (`MessagingConfig.java:129`, que só marca `JacksonException` e `EventoInvalidoException`), então o evento é retentado 3x (sempre falhando do mesmo jeito, já que é uma violação de constraint genuína) e acaba indo para a DLQ — mesmo tendo sido processado com sucesso.

**Sugestão:** capturar `DataIntegrityViolationException` no `save` do evento processado e tratar como no-op idempotente, no mesmo espírito do que já é feito em `SolicitarAssinatura.executar()` para o índice único de assinatura aberta.

### [Low] `usuarioId` sem validação de formato + log injection no controller

**Arquivos:** `services/assinatura/src/main/java/com/globo/assinatura/assinatura/AssinaturaRequest.java:12`, `AssinaturaController.java:44-49`

O OpenAPI (`docs/openapi/assinatura.yaml:55`) documenta que `400` cobre "uuid malformado" na solicitação, mas `AssinaturaRequest` só tem `@NotBlank String usuarioId` — sem `@Pattern` ou qualquer validação de formato. Uma string qualquer não-UUID passa pela validação, cai em `usuarioRepository.findByUuid(...)` e retorna `404` em vez do `400` documentado.

Esse mesmo valor não sanitizado é logado diretamente em `AssinaturaController.java:46-49` (`log.info(..., request.usuarioId(), ...)`) num endpoint `permitAll` (público, sem autenticação). Como não há validação de formato, é possível enviar um `usuarioId` contendo `\n`/caracteres de controle para forjar linhas de log (log injection, CWE-117).

**Sugestão:** adicionar `@Pattern` (ou `@UUID` de Hibernate Validator) no campo `usuarioId` do `AssinaturaRequest`, alinhando o comportamento ao contrato documentado e eliminando a superfície de log injection.

### [Low] `ClockConfig` não fixa timezone de negócio

**Arquivo:** `services/assinatura/src/main/java/com/globo/assinatura/assinatura/ClockConfig.java:23`

`Clock.systemDefaultZone()` depende do fuso horário do container em runtime, que não é fixado em nenhum lugar (sem `TZ` no `Dockerfile` nem no `docker-compose.yml`). `dataInicio`/`dataExpiracao` (`ProcessarPagamento.java:60`, `LocalDate.now(clock)`) ficam sujeitas a esse fuso implícito, o que pode divergir entre ambientes (dev local vs. imagem em produção) e deslocar a data de ativação em ±1 dia perto da meia-noite.

**Sugestão:** já que o `Clock` é injetado propositalmente para testabilidade, fixar o fuso de negócio explicitamente (ex.: `Clock.system(ZoneId.of("America/Sao_Paulo"))`) em vez de depender do padrão implícito da JVM/host.

## Padrões positivos (mantidos)

- Índice único parcial + catch de `DataIntegrityViolationException` cobre a regra "um usuário, uma assinatura aberta" sob concorrência sem precisar de lock explícito na criação.
- `Assinatura.ativar()`/`recusarPagamento()` são idempotentes por design (guard de status), absorvendo redelivery e eventos tardios sem duplo processamento observável no domínio.
- Outbox atômica: insert da assinatura e insert do evento `AssinaturaSolicitada` na mesma transação, sem dual-write.
- `ProcessarPagamento` ignora silenciosamente eventos para assinatura ainda inexistente, sem registrar idempotência — permite reprocessamento correto quando o evento de pagamento chega antes da solicitação (ordering eventual entre tópicos).
- Exceções de domínio (`UsuarioNaoEncontradoException`, `AssinaturaNaoEncontradaException`, `AssinaturaAbertaException`) não carregam identificadores na mensagem, cuidado consciente com exposição de dado em logs/erros.
- Boa cobertura de teste para os cenários de corrida (`SolicitarAssinaturaTest.deveLancarConflitoQuandoInsertViolaIndiceUnico`) e de redelivery/eventos tardios (`ProcessarPagamentoTest.deveIgnorarRedeliveryDeEventoJaProcessado`, `deveManterAssinaturaAtivaAoReceberEventoTardioRejeitado`).

## Como reproduzir

```bash
cd services/assinatura
make verify            # lint + testes unitarios
make test-integration  # sobe Postgres na 5433; valida repositorio + fluxo completo
```
