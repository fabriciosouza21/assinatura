CREATE TABLE cobranca_adesao_tentativa (
    id                    BIGSERIAL       PRIMARY KEY,
    assinatura_uuid       VARCHAR(36)     NOT NULL,
    valor                 NUMERIC(19,4)   NOT NULL,
    payment_id            VARCHAR(64),
    status                VARCHAR(32)     NOT NULL,
    falhas_tecnicas       INTEGER         NOT NULL DEFAULT 0,
    proxima_tentativa_em  TIMESTAMP,
    criado_em             TIMESTAMP       NOT NULL DEFAULT now(),
    atualizado_em         TIMESTAMP       NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_cobranca_adesao_tentativa_assinatura
    ON cobranca_adesao_tentativa (assinatura_uuid);

CREATE INDEX idx_cobranca_adesao_tentativa_pendente
    ON cobranca_adesao_tentativa (proxima_tentativa_em)
    WHERE status = 'PENDENTE' AND payment_id IS NULL;
