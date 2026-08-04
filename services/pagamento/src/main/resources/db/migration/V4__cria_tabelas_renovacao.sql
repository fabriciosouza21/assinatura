CREATE TABLE pagamento_renovacao (
    id               BIGSERIAL      PRIMARY KEY,
    renovacao_id     VARCHAR(36)    NOT NULL,
    assinatura_id    VARCHAR(36)    NOT NULL,
    plano            VARCHAR(32)    NOT NULL,
    valor            NUMERIC(19, 2) NOT NULL,
    ciclo_referencia INTEGER        NOT NULL,
    criado_em        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    atualizado_em    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_pagamento_renovacao_renovacao_id
    ON pagamento_renovacao (renovacao_id);

CREATE TABLE tentativa_cobranca (
    id                   BIGSERIAL      PRIMARY KEY,
    renovacao_id         VARCHAR(36)    NOT NULL,
    numero               INTEGER        NOT NULL,
    status               VARCHAR(32)    NOT NULL,
    payment_id           VARCHAR(64),
    proxima_tentativa_em TIMESTAMPTZ,
    criado_em            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    atualizado_em        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_tentativa_cobranca_renovacao_numero
    ON tentativa_cobranca (renovacao_id, numero);
