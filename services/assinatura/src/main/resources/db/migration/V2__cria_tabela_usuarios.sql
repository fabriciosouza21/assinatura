CREATE TABLE usuarios (
    id    BIGSERIAL    PRIMARY KEY,
    uuid  VARCHAR(36)  NOT NULL,
    nome  VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX uq_usuarios_email ON usuarios (email);
