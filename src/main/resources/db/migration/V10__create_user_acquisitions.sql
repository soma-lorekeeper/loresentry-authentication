CREATE TABLE user_acquisitions (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    utm_source varchar(100),
    utm_medium varchar(100),
    utm_campaign varchar(100),
    utm_term varchar(200),
    utm_content varchar(100),
    click_id varchar(255),
    click_id_type varchar(10) CHECK (click_id_type IN ('gclid', 'gbraid', 'wbraid')),
    referrer_host varchar(255),
    landing_path varchar(255),
    landed_at timestamptz,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT ck_user_acquisitions_click_id CHECK ((click_id IS NULL) = (click_id_type IS NULL))
);
