CREATE TABLE users (
    id             UUID         NOT NULL DEFAULT uuidv7(),
    google_subject VARCHAR(255) NOT NULL,
    email          VARCHAR(320) NOT NULL,
    display_name   VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_google_subject UNIQUE (google_subject),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DELETED'))
);

CREATE TABLE auth_sessions (
    id                 UUID         NOT NULL DEFAULT uuidv7(),
    user_id            UUID         NOT NULL,
    refresh_token_hash VARCHAR(255) NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ,
    CONSTRAINT pk_auth_sessions PRIMARY KEY (id),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_auth_sessions_user_id ON auth_sessions (user_id);
