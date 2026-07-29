CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

INSERT INTO users (username, password, role) VALUES
    ('admin', '$2a$10$LLJmkUlVM6pcZQckLeZlCuVUpURptbYPDRh5yAmKvxjSYhSafI3Gq', 'ROLE_ADMIN');
