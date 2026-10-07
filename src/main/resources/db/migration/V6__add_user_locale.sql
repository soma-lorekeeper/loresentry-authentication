ALTER TABLE users
    ADD COLUMN locale varchar(5) CONSTRAINT ck_users_locale CHECK (locale IN ('ko', 'en'));
