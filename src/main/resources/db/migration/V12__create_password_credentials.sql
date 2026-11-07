CREATE TABLE password_credentials (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash varchar(255) NOT NULL,
    created_at timestamptz NOT NULL
);
