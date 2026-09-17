CREATE TABLE users (
    id uuid PRIMARY KEY,
    display_name varchar(50) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE oauth_identities (
    provider varchar(32) NOT NULL,
    provider_id varchar(255) NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    email varchar(320),
    CONSTRAINT pk_oauth_identities PRIMARY KEY (provider, provider_id)
);

CREATE INDEX idx_oauth_identities_user_id ON oauth_identities(user_id);
