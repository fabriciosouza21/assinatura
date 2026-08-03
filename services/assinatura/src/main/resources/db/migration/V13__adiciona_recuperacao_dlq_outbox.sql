ALTER TABLE outbox ADD COLUMN ciclos_recuperacao SMALLINT NOT NULL DEFAULT 0;

DROP INDEX idx_outbox_polling;
CREATE INDEX idx_outbox_polling
    ON outbox (status, proxima_tentativa_em, criado_em)
    WHERE status IN ('PENDENTE', 'RETENTATIVA_DLQ');

CREATE INDEX idx_outbox_recuperacao_dlq
    ON outbox (falhou_em, ciclos_recuperacao)
    WHERE status = 'FALHA';
