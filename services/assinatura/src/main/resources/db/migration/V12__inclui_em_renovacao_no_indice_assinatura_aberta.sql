DROP INDEX uq_assinatura_aberta_usuario;

-- Deve espelhar SolicitarAssinatura.STATUS_ABERTOS.
CREATE UNIQUE INDEX uq_assinatura_aberta_usuario
    ON assinatura (usuario_id)
    WHERE status IN ('AGUARDANDO_PAGAMENTO', 'ATIVA', 'EM_RENOVACAO');
