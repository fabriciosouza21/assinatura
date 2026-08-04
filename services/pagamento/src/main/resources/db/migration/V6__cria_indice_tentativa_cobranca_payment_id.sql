-- Indice parcial para acelerar o webhook de renovacao, que ancora a decisao
-- no payment id notificado (findByPaymentId / buscarPorPaymentIdParaAtualizacao).
-- O indice parcial mantem o conjunto util pequeno conforme a tabela cresce,
-- espelhando o padrao de V3 para cobranca.
CREATE INDEX ix_tentativa_cobranca_payment_id
    ON tentativa_cobranca (payment_id)
    WHERE payment_id IS NOT NULL;
