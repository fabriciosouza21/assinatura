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
> Não há credenciais reais ou de produção envolvidas.

## Fluxo sugerido (mock gateway)

1. **Criar pagamento** → captura `paymentId` automaticamente (`after-response`).
2. **Consultar pagamento** → usa o `paymentId` capturado (status `PENDING`).
3. **Simular aprovação** → força `APPROVED` e dispara o webhook para o serviço
   de pagamento.
