CREATE TABLE cancelamento_evento_processado (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  assinatura_id UUID NOT NULL,
  processado_em TIMESTAMPTZ NOT NULL
);
