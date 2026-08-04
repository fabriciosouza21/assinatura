-- Indice parcial para acelerar o scheduler de renovacao (buscarVencidasParaRenovacao),
-- que seleciona assinaturas ATIVAs com proxima_renovacao_em vencida, ordenadas pela
-- propria data. O predicado parcial mantem o conjunto util pequeno conforme a tabela
-- cresce, espelhando o padrao de ix_tentativa_cobranca_pendente no servico de pagamento.
CREATE INDEX ix_assinatura_renovacao_vencida
    ON assinatura (proxima_renovacao_em)
    WHERE status = 'ATIVA';
