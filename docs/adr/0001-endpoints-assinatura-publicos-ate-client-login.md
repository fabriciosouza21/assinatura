# ADR 0001: Endpoints de assinatura públicos até login de cliente

**Date:** 2026-07-30
**Status:** Accepted
**Contexto:** BE-2 do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md`

## Context

O BE-2 introduz `POST /assinaturas` e `GET /assinaturas/{uuid}`. O
`SecurityConfig` exige autenticação em todas as rotas exceto `POST /usuarios`,
`/auth/login` e `/actuator/health`.

Existe `AuthService.login()` (`auth/AuthService.java`) que valida credenciais na
tabela `users` e emite JWT. O problema é que esse login **não serve a clientes**:
o único habitante de `users` hoje é o `admin` do seed. O BE-1 criou o agregado de
domínio `Usuario` (`uuid`, `nome`, `email`), mas **não** criou o `User` de auth
correspondente, nem senha. Os dois agregados não estão ligados. Logo, um cliente
recém-cadastrado tem um `usuario_uuid`, mas nenhuma credencial para chamar
`/auth/login`. Não existe principal de cliente para autenticar.

É preciso decidir como o cliente se autentica para solicitar assinatura **antes**
de codar o BE-2.

## Decision

`POST /assinaturas` e `GET /assinaturas/{uuid}` ficam públicos (`permitAll`),
amarrados ao método/rota no `SecurityConfig`, no mesmo molde de `POST /usuarios`.
O login de cliente é postergado para o fast-follow **FF-1**.

Justificativa:

1. **Consistência.** `POST /usuarios` já é self-service público. `POST
   /assinaturas` é a continuação natural do mesmo fluxo de iniciação do cliente.
2. **Sem movimento de dinheiro nesta etapa.** A chamada ao gateway de pagamento
   acontece no BE-5, *server-to-server*, dentro do Pagamento Service. O endpoint
   de assinatura apenas cria um registro `AGUARDANDO_PAGAMENTO`. O dano de não
   autenticar é baixo enquanto o fluxo não toca o gateway.
3. **Separação de preocupações.** Construir login de cliente é uma feature
   transversal que toca o agregado do BE-1 (ligar `User`↔`Usuario`, definir
   `username`/senha). Enfiá-la no BE-2 mistura auth com domínio de assinatura e
   dobra o escopo da entrega.
4. **Integridade preservada.** A regra "um usuário, uma assinatura aberta"
   (`409`) e o índice único parcial protegem a consistência dos dados
   independentemente de auth.

## Alternatives considered

- **(b) Criar login de cliente agora.** Resposta "correta" de longo prazo, mas é
  uma entrega própria: exige estender o cadastro do BE-1 para criar um `User`
  auth, ligar os agregados, decidir política de senha e expor login. Escopo
  incompatível com o BE-2 "sem fila". Vira o **FF-1**.
- **(c) Usar o `usuario_uuid` como credencial.** Descartada. Não é autenticação,
  é identificação sem prova de posse. Qualquer um que conheça um `usuario_uuid`
  age em nome do dono. *Security theater.*

## Consequences

- **Positivo:** BE-2 entrega limpo, sem acoplamento prematuro entre auth e
  domínio. Postura coerente com o cadastro público existente.
- **Negativo (dívida explícita):** qualquer um pode criar assinatura para
  qualquer `usuarioId` e ler qualquer assinatura pelo `uuid`. Mitigação: UUIDs
  têm 122 bits de entropia (enumeração impraticável); o endpoint não movimenta
  valor até o BE-5.
- **Follow-up obrigatório:** o **FF-1** (login de cliente) deve fechar a lacuna
  `User`↔`Usuario` **antes** do fluxo end-to-end acionar o gateway com
  transações reais. O FF-1 é pré-requisito para exigir auth nos endpoints de
  assinatura.
