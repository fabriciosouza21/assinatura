# PRD: Login de cliente

**Date:** 2026-07-30
**Status:** Draft
**Entrega:** FF-1 (Track C) do roadmap `docs/roadmap/2026-07-29-assinatura-fluxo-completo.md` e `docs/roadmap/2026-07-30-plano-implementacao-paralela.md`
**Contrato:** `docs/openapi/auth.yaml`

## Problem

O BE-1 entregou o cadastro de cliente (`POST /usuarios`), mas o `Usuario` de dominio
criado nao tem credencial. O unico habitante da tabela `users` e o `admin` seedado,
logo o `/auth/login` existente nao atende clientes. Um cliente recem-cadastrado recebe
um `usuario_uuid`, porem nao tem como se autenticar. E o bloqueador explicito do ADR
0001: nao existe principal de cliente para, no futuro, exigir autenticacao nos
endpoints de assinatura.

## Background

Existem dois agregados isolados:

- `usuarios` (dominio): `id`, `uuid`, `nome`, `email` (unico). Criado pelo BE-1.
- `users` (auth): `id`, `username` (`UNIQUE`, `VARCHAR(64)`), `password` (BCrypt),
  `role`. So existe o `admin` (`ROLE_ADMIN`).

`UsuarioService.cadastrar(nome, email)` cria apenas o `Usuario`. `AuthService.login`
busca `users` por `username`, valida a senha com `PasswordEncoder` (BCrypt) e emite um
JWT (subject = `username`, claim `role`) via `JwtService`. `/auth/login` e
`POST /usuarios` ja sao `permitAll` no `SecurityConfig`.

A ADR 0001 decidiu postergar o login de cliente para o fast-follow FF-1. Esta entrega e
o FF-1: estender o cadastro para criar tambem um `User` auth ligado ao `Usuario`,
habilitando `/auth/login` para clientes. E pre-requisito (nao a execucao) do passo
seguinte, que passara a exigir auth nos endpoints de assinatura.

## Requirements

### Must Have

- O cadastro (`POST /usuarios`) passa a exigir `senha`. Input: `{nome, email, senha}`.
  Senha obrigatoria, minimo 8 caracteres, validada.
- O cadastro cria, na mesma transacao, o `Usuario` (dominio) e um `User` (auth) com
  `username = email`, `password` = hash BCrypt da senha e `role = ROLE_CLIENT`.
- Os agregados ficam ligados: `users.usuario_id` referencia `usuarios.id`. O `admin`
  permanece com `usuario_id` `NULL`.
- `/auth/login` funciona para clientes: `POST /auth/login {username, password}` com
  `username = email` do cliente devolve `200` com JWT (subject = email,
  claim `role = ROLE_CLIENT`, mesmo `expiresInMs` do `admin`).
- Email unico continua garantindo `409` no cadastro. A unicidade de `username` em
  `users` (com `username = email`) e espelho do email, como backstop.
- A resposta do cadastro e inalterada: `201 {id: usuario_uuid}`. A senha nunca e
  devolvida.

### Should Have

- Login invalido (usuario inexistente ou senha errada) retorna `401 Unauthorized` com
  corpo vazio, via handler dedicado para `BadCredentialsException` (mesmo molde do
  handler de `409`).
- Validacao de senha (vazia, curta) retorna `400 Bad Request`.

### Out of Scope

- Passar `POST /assinaturas` e `GET /assinaturas/{uuid}` de `permitAll` para
  `authenticated()`/`hasRole('CLIENT')`. E o passo seguinte que consome o principal
  agora criado; fica como follow-up. A divida do ADR 0001 persiste ate la.
- Corrigir o `JwtAuthenticationFilter` para honrar o claim `role` do JWT. Hoje ele
  forca `ROLE_USER` (linha 49), ignorando o claim. So e necessario quando o follow-up
  acima exigir roles; registrado como divida.
- Recuperacao/troca de senha, refresh, logout, bloqueio apos tentativas e 2FA.
- Edicao, listagem e remocao de usuarios.

## Constraints

- O schema e Flyway-owned (`ddl-auto: validate`). Mudancas exigem migration `V4`:
  ampliar `users.username` para `VARCHAR(255)` (emails chegam a 254), adicionar
  `users.usuario_id BIGINT REFERENCES usuarios(id)` e indice unico parcial para
  garantir 1:1 (`WHERE usuario_id IS NOT NULL`).
- O `id` Long interno permanece a PK; o `uuid` e a identidade publica.
- A maquinaria `Problem` (RFC 7807) ainda nao esta no `develop` (e codigo do BE-2). O
  `400` de validacao, por enquanto, usa o comportamento padrao do Spring; quando o
  BE-2 mergear, o handler global cobre o caso sem retrabalho.
- Convencoes do projeto: Javadoc obrigatorio em todo publico, Google Java Style,
  CQRS Lite (command de escrita), senhas sempre hasheadas com BCrypt.

## Decisoes

- **Identificador de login = email (D1).** O cliente faz login com `email` + `senha`;
  `users.username` guarda o email. Evita introduzir um username separado e reaproveita
  a unicidade ja existente. O `admin` continua `username='admin'`. *(Confirmar.)*
- **Cadastro passa a exigir senha (D2).** Input `{nome, email, senha}`, senha
  `@NotBlank @Size(min=8)`, hasheada com BCrypt. Breaking change no contrato do BE-1.
- **Link `User` <-> `Usuario` por FK nullable (D3).** `users.usuario_id -> usuarios(id)`,
  `NULL` para o `admin`; 1:1 por indice unico parcial. Ambos na mesma transacao.
- **Role do cliente = `ROLE_CLIENT` (D4).** `admin = ROLE_ADMIN`. O JWT carrega
  `role=ROLE_CLIENT`.
- **Escopo = login de cliente apenas (D5).** O flip dos endpoints de assinatura para
  autenticados e follow-up. *(Confirmar se entra nesta entrega.)*
- **Login invalido -> 401 corpo vazio (D6).** Handler dedicado para
  `BadCredentialsException`, no molde do handler de `409`.

## Acceptance Criteria

### Cadastro cria credencial

- Given um cliente sem cadastro, when envia `POST /usuarios {nome, email, senha}`
  validos, then recebe `201 {id: uuid}` e existem na base, na mesma transacao, o
  `Usuario` e um `User` com `username=email`, `password` hasheado (BCrypt) e
  `role=ROLE_CLIENT`, com `users.usuario_id` apontando para o `usuarios.id`.

### Cliente loga

- Given um cliente cadastrado, when envia
  `POST /auth/login {username: email, password: senha}`, then recebe `200` com
  `{token, tokenType:"Bearer", expiresInMs}` e o JWT decodificado tem subject=email e
  claim `role=ROLE_CLIENT`.

### Login invalido

- Given credenciais invalidas (email inexistente ou senha errada), when envia
  `/auth/login`, then recebe `401` com corpo vazio.

### Senha invalida no cadastro

- Given um cadastro com senha vazia ou com menos de 8 caracteres, when enviado,
  then recebe `400 Bad Request`.

### Email duplicado

- Given um email ja cadastrado, when envia `POST /usuarios`, then recebe `409` e
  nenhum `User` e criado (atomicidade da transacao).

### Admin preservado

- Given a migration `V4` aplicada, when o servico sobe, then o `admin` segue logando
  com `username='admin'` e `usuario_id` `NULL`.

### Unicidade do link

- Given um `Usuario` ja com `User`, when qualquer fluxo tenta criar um segundo `User`
  para o mesmo `usuario_id`, then o indice unico rejeita (1:1).
