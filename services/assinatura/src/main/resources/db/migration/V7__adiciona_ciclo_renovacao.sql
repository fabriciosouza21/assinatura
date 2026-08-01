ALTER TABLE assinatura
    ADD COLUMN inicio_ciclo         DATE,
    ADD COLUMN fim_ciclo            DATE,
    ADD COLUMN proxima_renovacao_em DATE,
    ADD COLUMN renovacao_automatica BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE renovacao (
    id               BIGSERIAL    PRIMARY KEY,
    assinatura_id    BIGINT       NOT NULL REFERENCES assinatura (id),
    ciclo_referencia DATE         NOT NULL,
    status           VARCHAR(32)  NOT NULL
);

CREATE UNIQUE INDEX uq_renovacao_assinatura_ciclo
    ON renovacao (assinatura_id, ciclo_referencia);
