CREATE TABLE cobranca (
    id             BIGSERIAL    PRIMARY KEY,
    assinatura_uuid VARCHAR(36) NOT NULL,
    payment_id     VARCHAR(64)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    criado_em      TIMESTAMP    NOT NULL DEFAULT now(),
    atualizado_em  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cobranca_assinatura ON cobranca (assinatura_uuid);
