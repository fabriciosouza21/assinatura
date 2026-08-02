# ADR 0003: Endpoints de assinatura exigem JWT do dono

**Date:** 2026-08-01
**Status:** Accepted
**Supersedes:** [ADR 0001](0001-endpoints-assinatura-publicos-ate-client-login.md)

## Context

A ADR 0001 deixou `POST /assinaturas` e `GET /assinaturas/{uuid}` públicos porque
não existia login de cliente: o `Usuario` de domínio não tinha credencial ligada,
e o único habitante de `users` era o `admin` do seed. A própria ADR registrou a
dívida — *"qualquer um pode criar assinatura para qualquer `usuarioId` e ler
qualquer assinatura pelo `uuid`"* — e definiu o FF-1 como pré-requisito para
exigir auth.

O FF-1 foi entregue. `UsuarioService.cadastrar` cria, na mesma transação, o
`Usuario` de domínio e o `User` de auth (`username = email`, `role = ROLE_CLIENT`,
`usuario_id` ligado), e `POST /auth/login` emite JWT para clientes. A premissa
que sustentava a ADR 0001 deixou de valer.

Ao mesmo tempo, o fluxo passou a movimentar dinheiro: a renovação automática cria
cobranças recorrentes no gateway, e o cancelamento encerra um contrato. São
operações que não podem depender apenas da entropia de um uuid.

## Decision

Todas as rotas de assinatura exigem JWT, e o dono do recurso vem do token.

1. **Nenhuma rota de assinatura é pública.** O `permitAll` fica restrito a
   `POST /usuarios` (auto-cadastro), `/auth/login` e `/actuator/health`.
2. **O dono sai do token, não do corpo.** `POST /assinaturas` deixa de receber
   `usuarioId`: o corpo carrega apenas `plano`. Não existe requisição capaz de
   expressar "assinar em nome de outro" — a classe de falha é eliminada por
   construção, não por validação.
3. **O token carrega a identidade do domínio.** O login emite a claim
   `usuarioId` com o uuid público do `Usuario` ligado à credencial, resolvida a
   partir de `users.usuario_id`. A autorização por requisição não custa consulta
   ao banco.
4. **Consulta é do dono.** `GET /assinaturas/{uuid}` devolve `403` quando a
   assinatura pertence a outro usuário. `ROLE_ADMIN` consulta qualquer uma, o que
   exigiu o filtro passar a derivar a authority da claim `role` em vez de fixar
   `ROLE_USER`.
5. **`401` e `403` têm significados distintos.** Token ausente, expirado ou
   inválido resulta em `401`, via `HttpStatusEntryPoint`; `403` fica reservado a
   quem está autenticado mas não é dono do recurso.

O escopo é o Assinatura Service. O `GET /cobrancas/{assinaturaId}` do Pagamento
Service segue público como apoio ao fluxo de teste, e o webhook do gateway segue
autenticado por HMAC, que é o mecanismo adequado para chamada server-to-server.

## Alternatives considered

- **(a) Manter `usuarioId` no corpo e validar contra o token.** Preserva o
  contrato, mas mantém um campo redundante cuja checagem alguém pode esquecer em
  um endpoint futuro. Derivar do token remove a decisão do caminho de código.
- **(b) Resolver o dono por e-mail a cada requisição.** Dispensa mudança no
  token, ao custo de um `SELECT` por requisição autenticada. A claim entrega o
  mesmo dado sem round-trip, e o token já é assinado.
- **(c) Devolver `404` em vez de `403` para assinatura de terceiro.** Não revela
  a existência do recurso, mas confunde o cliente legítimo com uuid correto e
  contradiz o contrato publicado em `docs/openapi/`.

## Consequences

- **Positivo:** a dívida declarada na ADR 0001 está fechada. Não há caminho para
  criar assinatura em nome de terceiro nem para ler assinatura alheia.
- **Positivo:** o cancelamento (`docs/openapi/cancelamento-assinatura.yaml`)
  passa a ter base: é uma operação sobre contrato que já nasce autenticada.
- **Negativo (quebra de contrato):** `AssinaturaRequest` perde `usuarioId`.
  Clientes existentes precisam autenticar antes de assinar. A collection do
  Bruno passa a fazer cadastro → login → assinatura, logando como o cliente
  criado em vez do `admin`.
- **Negativo:** o `admin` do seed não tem `Usuario` ligado e, portanto, não pode
  assinar (`403` no `POST`). Ele permanece útil para consultar assinaturas de
  qualquer cliente.
