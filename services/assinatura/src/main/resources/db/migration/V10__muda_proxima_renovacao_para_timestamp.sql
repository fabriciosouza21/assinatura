ALTER TABLE assinatura
    ALTER COLUMN proxima_renovacao_em TYPE TIMESTAMPTZ
        USING proxima_renovacao_em::timestamptz;
