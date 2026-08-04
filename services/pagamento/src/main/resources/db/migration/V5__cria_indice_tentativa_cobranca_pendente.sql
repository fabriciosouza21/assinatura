-- Indice parcial para acelerar a selecao do scheduler de tentativas de cobranca.
-- O predicado atual filtra por payment_id IS NULL; o indice parcial mantem o
-- conjunto util pequeno conforme a tabela cresce, indexando a coluna temporal que
-- participa do filtro de elegibilidade (proxima_tentativa_em vencida).
CREATE INDEX ix_tentativa_cobranca_pendente
    ON tentativa_cobranca (proxima_tentativa_em)
    WHERE status = 'PENDENTE' AND payment_id IS NULL;
