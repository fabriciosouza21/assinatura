CREATE TABLE pagamento_evento_processado (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL,
    assinatura_uuid VARCHAR(36)  NOT NULL,
    processado_em   TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_pagamento_evento_processado_event_id
    ON pagamento_evento_processado (event_id);
