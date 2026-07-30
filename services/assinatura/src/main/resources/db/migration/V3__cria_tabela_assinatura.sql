CREATE TABLE assinatura (
    id             BIGSERIAL    PRIMARY KEY,
    uuid           VARCHAR(36)  NOT NULL,
    usuario_id     BIGINT       NOT NULL REFERENCES usuarios (id),
    plano          VARCHAR(16)  NOT NULL,
    data_inicio    DATE,
    data_expiracao DATE,
    status         VARCHAR(32)  NOT NULL
);

CREATE UNIQUE INDEX uq_assinatura_uuid ON assinatura (uuid);

CREATE UNIQUE INDEX uq_assinatura_aberta_usuario
    ON assinatura (usuario_id)
    WHERE status IN ('AGUARDANDO_PAGAMENTO', 'ATIVA');
