CREATE TABLE renovacao_evento_processado (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    renovacao_uuid  VARCHAR(36)  NOT NULL,
    processado_em   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_renovacao_evento_processado_event_id
    ON renovacao_evento_processado (event_id);
