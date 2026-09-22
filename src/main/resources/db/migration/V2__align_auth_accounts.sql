-- V1 is already deployed with empty tables; retain its migration history.
-- Refresh-token state is stored in Redis by the current implementation.
DROP TABLE auth_sessions;

ALTER TABLE users
    DROP COLUMN google_subject,
    DROP COLUMN email,
    DROP COLUMN status,
    ALTER COLUMN display_name TYPE varchar(50),
    ALTER COLUMN id DROP DEFAULT,
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT;

CREATE TABLE oauth_identities (
    provider varchar(32) NOT NULL,
    provider_id varchar(255) NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    email varchar(320),
    CONSTRAINT pk_oauth_identities PRIMARY KEY (provider, provider_id)
);

CREATE INDEX idx_oauth_identities_user_id ON oauth_identities(user_id);
