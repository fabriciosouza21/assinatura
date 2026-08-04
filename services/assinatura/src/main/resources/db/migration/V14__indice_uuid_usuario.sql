-- O uuid publico do usuario e a chave de lookup de todos os caminhos de leitura
-- do cliente (SolicitarAssinatura, ListarAssinaturas, ConsultarAssinaturaAtiva),
-- mas a tabela so indexava email. Cada findByUuid fazia seq scan. UNIQUE tambem
-- protege a integridade: o uuid e identificador publico do agregado.
CREATE UNIQUE INDEX uq_usuarios_uuid ON usuarios (uuid);
