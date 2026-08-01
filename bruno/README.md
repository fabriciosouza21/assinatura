# Collection Bruno

Collection no formato [OpenCollection YAML](https://docs.usebruno.com/opencollection-yaml/overview)
do [Bruno](https://docs.usebruno.com/). Git-friendly, em texto plano.

## Como usar

1. Suba o ambiente: na raiz do repo, `docker compose up -d --build`.
2. No Bruno: **Open Collection** e selecione a pasta `bruno/`.
3. Selecione o environment **Local** (no canto superior direito).

As variáveis de ambiente apontam para as portas do compose:
`assinaturaUrl` (18080), `pagamentoUrl` (18082), `mockGatewayUrl` (8081).

> **Credenciais de teste**: o environment Local já vem com `password: admin123`
> (variável marcada como `secret`). Essa senha é o seed de desenvolvimento
> definido em `services/assinatura/src/main/resources/db/migration/V1__init.sql`
> e foi versionada **intencionalmente** para facilitar os testes da collection.
> A mesma senha é reaproveitada no cadastro do cliente de teste. Não há
> credenciais reais ou de produção envolvidas.

## Fluxo sugerido (assinatura)

1. **Cadastrar usuario** → cria o cliente de teste e captura `usuarioId`
   automaticamente (`after-response`).
2. **Login** → autentica **como o cliente cadastrado** e captura `token`.
3. **Solicitar assinatura** → envia `Bearer {{token}}` e apenas o `plano`;
   devolve a assinatura com status `AGUARDANDO_PAGAMENTO` e captura
   `assinaturaId`.
4. **Consultar assinatura** → usa o `assinaturaId` capturado, também com
   `Bearer {{token}}`, para acompanhar o estado da assinatura.

> As rotas de assinatura exigem JWT (ADR 0003): sem o passo de **Login** elas
> respondem `401`. O dono vem do token, então `usuarioId` não é mais enviado no
> corpo da solicitação. O `admin` do seed não serve para assinar — não tem
> usuário de domínio ligado e recebe `403` no `POST /assinaturas`.

> Os planos aceitos são `BASICO`, `PREMIUM` e `FAMILIA`. O status parte de
> `AGUARDANDO_PAGAMENTO` e migra para `ATIVA` ou `PAGAMENTO_RECUSADO` conforme
> o processamento do pagamento.

## Fluxo sugerido (mock gateway)

1. **Criar pagamento** → captura `paymentId` automaticamente (`after-response`).
2. **Consultar pagamento** → usa o `paymentId` capturado (status `PENDING`).
3. **Simular aprovação** → força `APPROVED` e dispara o webhook para o serviço
   de pagamento.

## Fluxo encadeado completo (assinatura)

Ponta a ponta, do cadastro do usuário até a assinatura `ATIVA`, sem editar
variáveis manualmente entre os passos. Cada request captura automaticamente o
id do passo seguinte via `after-response`.

1. **Cadastrar usuário** → captura `usuarioId`.
2. **Login** → autentica como o cliente cadastrado e captura `token`.
3. **Solicitar assinatura** → envia `Bearer {{token}}` e o `plano`; devolve a
   assinatura com status `AGUARDANDO_PAGAMENTO` e captura `assinaturaId`.
4. **Consultar cobrança** (`pagamento/consultar-cobranca`) → usa o `assinaturaId`
   capturado e devolve o `paymentId` gerado no gateway; captura `paymentId`.
5. **Simular aprovação** (`mock-gateway/simular-aprovacao`) → usa o `paymentId`
   capturado, força `APPROVED` e dispara o webhook que leva a assinatura a
   `ATIVA`.
6. **Consultar assinatura** → usa o `assinaturaId` capturado, com o mesmo token,
   para confirmar o status `ATIVA`.

> O passo **Consultar cobrança** é a ponte entre os dois serviços: sem ele, o
> `paymentId` fica preso no Pagamento Service e não há como aprovar a cobrança
> no mock. O `assinaturaId` é o uuid público da assinatura, o mesmo valor usado
> como `Idempotency-Key` e `externalReference` no fluxo do mock.
