-- Vincula o agregado de auth (users) ao agregado de dominio (usuarios).
-- O admin seedado permanece com usuario_id NULL; clientes tem link 1:1.
ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(255);

ALTER TABLE users
    ADD COLUMN usuario_id BIGINT REFERENCES usuarios(id);

-- Indice unico parcial garante 1:1 entre Usuario e User, permitindo multiplos
-- registros com usuario_id NULL (caso do admin).
CREATE UNIQUE INDEX uq_users_usuario ON users(usuario_id) WHERE usuario_id IS NOT NULL;
