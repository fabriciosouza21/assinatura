ALTER TABLE renovacao
    ADD COLUMN uuid         VARCHAR(36) NOT NULL,
    ADD COLUMN numero_ciclo INTEGER     NOT NULL;

CREATE UNIQUE INDEX uq_renovacao_uuid
    ON renovacao (uuid);
