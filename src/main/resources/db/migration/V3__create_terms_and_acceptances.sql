CREATE TABLE terms_versions (
    id uuid PRIMARY KEY,
    terms_type varchar(32) NOT NULL CHECK (terms_type = 'SERVICE_TERMS'),
    version varchar(100) NOT NULL,
    title text NOT NULL,
    content text NOT NULL,
    published_at timestamptz NOT NULL,
    effective_at timestamptz NOT NULL,
    CONSTRAINT uq_terms_version UNIQUE (terms_type, version),
    CONSTRAINT uq_terms_effective_at UNIQUE (terms_type, effective_at),
    CONSTRAINT ck_terms_publication CHECK (published_at <= effective_at)
);

CREATE TABLE user_terms_acceptances (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    terms_version_id uuid NOT NULL REFERENCES terms_versions(id),
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, terms_version_id)
);

-- Publish approved originals through INSERTs in new versioned migrations.
-- No draft or test original is activated by this schema migration.
