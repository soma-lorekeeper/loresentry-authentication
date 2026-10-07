CREATE TABLE terms_version_translations (
    terms_version_id uuid NOT NULL REFERENCES terms_versions(id) ON DELETE CASCADE,
    locale varchar(5) NOT NULL CHECK (locale IN ('en')),
    title text NOT NULL,
    content text NOT NULL,
    PRIMARY KEY (terms_version_id, locale)
);

-- Translations never create new terms versions; consent is still recorded against terms_versions.
-- Publish approved translations through INSERTs in new versioned migrations.
