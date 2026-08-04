CREATE TABLE webhook_evento_processado (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    assinatura_id   UUID         NOT NULL,
    processado_em   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_webhook_evento_processado_event_id
    ON webhook_evento_processado (event_id);
