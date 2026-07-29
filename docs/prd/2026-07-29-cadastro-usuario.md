# PRD: Cadastro de usuário

**Date:** 2026-07-29
**Status:** Draft
**Entrega:** BE-1 do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md`

## Problem

Hoje só existe o usuário `admin` seedado na base, usado exclusivamente para
emitir o JWT de administração. Não há forma de registrar um cliente no sistema.
Sem o cadastro de cliente, não é possível iniciar o fluxo de assinatura do
desafio (solicitar assinatura exige um `usuarioId`).

## Background

O diagrama `docs/cadastro-usuario-assinatura.puml` define o contrato
`POST /usuarios {nome, email} -> 201 {id: usuario_uuid}` como ponto de entrada
do fluxo completo. A tabela `users` atual nasceu orientada a login
(`username`, `password`, `role`) e não tem perfil de cliente nem identificador
público. Esta entrega adiciona o perfil de cliente e o `uuid` externo, mantendo
o `id` Long interno e o seed `admin` intactos.

## Requirements

### Must Have
- Cliente cadastra-se enviando `nome` e `email` via `POST /usuarios`, sem
  autenticação.
- Resposta `201 Created` com o identificador público do usuário
  (`{id: uuid}`).
- Email é único. Cadastro com email já existente retorna `409 Conflict`.
- `nome` e `email` são obrigatórios e validados (não vazios, email em formato
  válido).
- Cada usuário recém-cadastrado recebe um `uuid` gerado automaticamente,
  exposto em todas as respostas da API.

### Should Have
- Tratamento de erros padronizado (400 para validação, 409 para duplicado) via
  handler global, reutilizável pelas próximas entregas (assinatura).

### Out of Scope
- Senha e capacidade de login para clientes cadastrados. Só `admin` autentica
  via `/auth/login`.
- Autenticação obrigatória no cadastro (self-registration público por design).
- Edição, listagem e remoção de usuários.

## Constraints

- O schema é Flyway-owned (`ddl-auto: validate`). Qualquer mudança de coluna
  exige uma nova migration `V2`.
- O `id` Long interno permanece a chave primária para eficiência em joins e
  buscas internas. O `uuid` é apenas a identidade pública exposta na API.
- Convenções do projeto: Javadoc obrigatório em todo público, Google Java Style,
  CQRS Lite (command de escrita).

## Acceptance Criteria

### Cadastro bem-sucedido
- Given um cliente sem cadastro, when envia `POST /usuarios {nome, email}`
  válidos sem token, then recebe `201 Created` com `{id: <uuid>}` e o usuário
  aparece na base com `uuid`, `nome` e `email`.

### Email duplicado
- Given um email já cadastrado, when envia `POST /usuarios` com esse email,
  then recebe `409 Conflict`.

### Validação de entrada
- Given um corpo inválido (nome vazio, email malformado, ou campos ausentes),
  when envia `POST /usuarios`, then recebe `400 Bad Request`.

### Preservação do admin
- Given a migration aplicada, when o serviço sobe, then o usuário `admin`
  seedado continua existente e funcional para login.

### Identidade pública
- Given um usuário cadastrado, when a API responde, then ela expõe apenas o
  `uuid`, nunca o `id` Long interno.
