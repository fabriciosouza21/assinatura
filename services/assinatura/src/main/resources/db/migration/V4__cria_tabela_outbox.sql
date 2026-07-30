CREATE TABLE outbox (
    event_id              UUID         PRIMARY KEY,
    aggregate_type        VARCHAR(64)  NOT NULL,
    aggregate_id          UUID         NOT NULL,
    event_type            VARCHAR(64)  NOT NULL,
    payload               JSONB        NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    tentativas            SMALLINT     NOT NULL DEFAULT 0,
    proxima_tentativa_em  TIMESTAMPTZ  NOT NULL,
    criado_em             TIMESTAMPTZ  NOT NULL,
    publicado_em          TIMESTAMPTZ,
    falhou_em             TIMESTAMPTZ,
    ultimo_erro           TEXT
);

CREATE INDEX idx_outbox_polling
    ON outbox (status, proxima_tentativa_em, criado_em)
    WHERE status = 'PENDENTE';
